package dev.miubridge.core

import org.json.JSONArray
import org.json.JSONObject

/** What MCP clients get: the phone tools plus the shared app manuals, listed in the instructions. */
class BridgeTools(private val context: android.content.Context, private val phone: CommandHandler, private val skills: Skills) : CommandHandler {
    private val scripts = ScriptRunner(context, this)
    private val consent = AppConsent(context)

    private val skillSpecs = mapOf(
        "phone_skills" to CommandSpec("List the apps agents have taken notes on (skills): name, description and the app packages each one covers. An app nobody has used yet is not listed."),
        "phone_skill_read" to CommandSpec(
            "Read an app manual before using that app: what the app is, how its screens are organized, known pitfalls, " +
                "and facts other agents learned on this phone.",
            required = mapOf("name" to Param("string", "Skill name from phone_skills, e.g. wechat.")),
            optional = mapOf("file" to Param("string", "A file under the skill's references/ the manual points to, e.g. references/send-moments.md.")),
        ),
        "phone_skill_learn" to CommandSpec(
            "Record one fact you learned about an app on this phone, so the next agent knows it: a screen, an entry point, " +
                "a coordinate, a pitfall. Write a fact, not an instruction, in one line. Never record chat contents, names of " +
                "people, codes or other private data.",
            required = mapOf(
                "name" to Param("string", "Skill name, e.g. wechat. For an app without a manual, a new lowercase-hyphen name."),
                "fact" to Param("string", "One line, at most 300 characters."),
            ),
            optional = mapOf(
                "description" to Param("string", "Only when creating a new skill: the app's name, package and what it is for."),
                "replaces" to Param("string", "To correct an earlier fact: a distinctive part of its text. That fact is removed and this one takes its place."),
            ),
        ),
        "phone_skill_forget" to CommandSpec(
            "Remove one learned fact that turned out wrong or out of date (the app changed, a coordinate moved), so it stops " +
                "misleading the next agent. To replace it with the right fact, use phone_skill_learn with `replaces` instead.",
            required = mapOf(
                "name" to Param("string", "Skill name, e.g. wechat."),
                "fact" to Param("string", "A distinctive part of the fact's text; it must match exactly one fact."),
            ),
        ),
    )

    override fun specs(): Map<String, CommandSpec> = phone.specs() + skillSpecs + (ScriptRunner.TOOL to ScriptRunner.SPEC)

    override fun guideMarkdown(): String = phone.guideMarkdown() + "\n\n" + ScriptRunner.GUIDE + "\n\n" + """
        ## Two ways in, one phone

        These tools are also available from scripts in your own sandbox, with the same URL and token you connected
        with: a Python or JavaScript SDK at ${dev.miubridge.server.Account(context).baseUrl}/sdk/ turns `phone.tap("text=Send")` into the same
        tool call. Use tool calls, scripts on the phone (phone_script), or scripts in your sandbox, as suits the task.

        ## When a call fails or gets no answer

        An error ends with `[code: …]` (also in `structuredContent.code`; in a script, `e.code`). Branch on the code, not the wording:
        `phone_locked` (the owner must unlock), `display_not_ready`, `helper_failed`, `reader_unavailable`,
        `not_found` (no such element, or a ref that is gone), `ambiguous` (several match; add `>> nth=N`),
        `not_actionable` (disabled or off-screen), `wait_timeout`, `timeout` (it may or may not have happened),
        `paced` (stop in this app for now), `owner_consent_needed` (the owner has to allow this app on the phone
        first; tell them, then call again), `quota_exceeded` (the owner's monthly allowance of remote calls is used
        up; only through the relay, not on the phone's own Wi-Fi), `blocked_app`, `not_installed`, `on_main_display`, `bad_request`,
        `unknown_tool`, `script_error` (the script's own mistake or its time limit; a script stopped by a failed
        tool call carries that call's code instead), `failed`.
        `phone_status` says what state the phone is in without touching it; ask it instead of guessing from an error.

        If a request times out on the way, you cannot know whether the action happened. Any tool takes an optional
        `request_id` (a string you make up, new for each action): send the same call again with the same id and you get
        the first answer back, and the action is not done twice. The SDKs do this for you.

    """.trimIndent() + "\n" + """
        ## App manuals

        Manuals are facts that agents learned on this phone; an app nobody has used yet has none.
        Before using an app, read its manual with phone_skill_read if it has one.
        When you learn something about an app that would have saved you steps, record it with phone_skill_learn.
        When a recorded fact turns out wrong, fix it: phone_skill_learn with `replaces`, or phone_skill_forget.

    """.trimIndent() + "\n" + skills.index()

    /** Told about every finished call, from any transport (the opt-in experience improvement program listens). */
    var onCall: ((name: String, params: JSONObject, result: CommandResult, ms: Long) -> Unit)? = null

    override suspend fun invoke(name: String, params: JSONObject): CommandResult {
        val start = android.os.SystemClock.elapsedRealtime()
        val result = consent.check(name, params) ?: dispatch(name, params)
        runCatching { onCall?.invoke(name, params, result, android.os.SystemClock.elapsedRealtime() - start) }
        return result
    }

    private suspend fun dispatch(name: String, params: JSONObject): CommandResult = try {
        when (name) {
            "phone_skills" -> CommandResult.Ok(JSONObject().put("skills", JSONArray().apply {
                skills.list().forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("packages", JSONArray(it.packages))) }
            }))
            "phone_skill_read" -> CommandResult.Ok(JSONObject().put("skill", skills.read(params.getString("name"), params.optString("file").ifBlank { null })))
            "phone_skill_learn" -> CommandResult.Ok(JSONObject().put("done",
                skills.learn(params.getString("name"), params.getString("fact"), params.optString("description").ifBlank { null },
                    params.optString("replaces").ifBlank { null })))
            "phone_skill_forget" -> CommandResult.Ok(JSONObject().put("done", skills.forget(params.getString("name"), params.getString("fact"))))
            ScriptRunner.TOOL -> scripts.run(params.getString("code")).let { r ->
                val payload = JSONObject().put("output", r.output)
                if (r.images.isNotEmpty()) payload.put("screenshots", r.images.size)
                // A failed script still reports what it printed and how far it got: its earlier tool calls did happen.
                if (r.error == null) CommandResult.Ok(payload, r.images)
                else CommandResult.Error("Script error after ${r.calls} successful call(s), which are not undone: ${r.error}\n${r.output}".trim(), r.code ?: "script_error")
            }
            else -> phone.invoke(name, params)
        }
    } catch (e: IllegalArgumentException) {
        CommandResult.Error(e.message ?: "bad arguments", "bad_request")
    } catch (e: org.json.JSONException) {
        CommandResult.Error("missing argument: ${e.message}", "bad_request")
    }
}
