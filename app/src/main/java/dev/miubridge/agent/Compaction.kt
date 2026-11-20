package dev.miubridge.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Context compaction, following pi-coding-agent's design (MIT, github.com/badlogic/pi-mono): summarize older
 * messages, keep about [Settings.keepRecentTokens] of recent ones verbatim, never cut between a tool call and
 * its result, and build each summary on the previous one. The prompts are pi's, adapted to a phone.
 */
object Compaction {
    data class Settings(val enabled: Boolean = true, val reserveTokens: Int = 16_384, val keepRecentTokens: Int = 20_000)

    class Preparation(
        val firstKeptEntryId: String,
        val messagesToSummarize: List<JSONObject>,
        val turnPrefixMessages: List<JSONObject>,
        val isSplitTurn: Boolean,
        val tokensBefore: Int,
        val previousSummary: String?,
        val readFiles: Set<String>,
        val modifiedFiles: Set<String>,
    )

    class Result(val summary: String, val firstKeptEntryId: String, val tokensBefore: Int, val details: JSONObject)

    fun shouldCompact(contextTokens: Int, contextWindow: Int, s: Settings) = s.enabled && contextTokens > contextWindow - s.reserveTokens

    /** The last valid usage plus estimates for anything after it (pi's estimateContextTokens). */
    fun estimateContextTokens(messages: List<JSONObject>): Int {
        val last = messages.indexOfLast { it.optString("role") == "assistant" && it.optJSONObject("usage") != null &&
            it.optString("stopReason") !in setOf("error", "aborted") }
        if (last < 0) return messages.sumOf { Messages.estimateTokens(it) }
        val usage = messages[last].getJSONObject("usage").optInt("totalTokens")
        return usage + (last + 1 until messages.size).sumOf { Messages.estimateTokens(messages[it]) }
    }

    private fun role(e: JSONObject) = if (e.optString("type") == "message") e.optJSONObject("message")?.optString("role") else null

    /** Walks back from the newest message until keepRecentTokens is reached; cuts at a user or assistant message. */
    private fun findCutPoint(path: List<JSONObject>, start: Int, end: Int, keepRecent: Int): Triple<Int, Int, Boolean> {
        val cutPoints = (start until end).filter { role(path[it]) in setOf("user", "assistant") }
        if (cutPoints.isEmpty()) return Triple(start, -1, false)
        var cut = cutPoints.first()
        var acc = 0
        for (i in end - 1 downTo start) {
            val m = path[i].takeIf { it.optString("type") == "message" }?.optJSONObject("message") ?: continue
            acc += Messages.estimateTokens(m)
            if (acc >= keepRecent) {
                cut = cutPoints.firstOrNull { it >= i } ?: cut
                break
            }
        }
        while (cut > start) {
            val prev = path[cut - 1]
            if (prev.optString("type") == "compaction" || prev.optString("type") == "message") break
            cut--
        }
        val isUser = role(path[cut]) == "user"
        val turnStart = if (isUser) -1 else (cut downTo start).firstOrNull { role(path[it]) == "user" } ?: -1
        return Triple(cut, turnStart, !isUser && turnStart != -1)
    }

    fun prepare(path: List<JSONObject>, s: Settings): Preparation? {
        if (path.isEmpty() || path.last().optString("type") == "compaction") return null
        val prevIndex = path.indexOfLast { it.optString("type") == "compaction" }
        var previousSummary: String? = null
        var start = 0
        val read = LinkedHashSet<String>()
        val modified = LinkedHashSet<String>()
        if (prevIndex >= 0) {
            val prev = path[prevIndex]
            previousSummary = prev.optString("summary")
            val kept = path.indexOfFirst { it.optString("id") == prev.optString("firstKeptEntryId") }
            start = if (kept >= 0) kept else prevIndex + 1
            prev.optJSONObject("details")?.let { d ->
                d.optJSONArray("readFiles")?.let { a -> for (i in 0 until a.length()) read += a.getString(i) }
                d.optJSONArray("modifiedFiles")?.let { a -> for (i in 0 until a.length()) modified += a.getString(i) }
            }
        }
        val tokensBefore = estimateContextTokens(Session.buildContext(path))
        val (firstKept, turnStart, split) = findCutPoint(path, start, path.size, s.keepRecentTokens)
        val firstKeptId = path[firstKept].optString("id").ifEmpty { return null }
        val historyEnd = if (split) turnStart else firstKept
        fun messagesIn(range: IntRange) = range.mapNotNull { i ->
            path[i].takeIf { it.optString("type") == "message" }?.optJSONObject("message")
        }
        val toSummarize = messagesIn(start until historyEnd)
        val prefix = if (split) messagesIn(turnStart until firstKept) else emptyList()
        (toSummarize + prefix).filter { it.optString("role") == "assistant" }.forEach { m ->
            Messages.toolCalls(m).forEach { c ->
                val p = c.optJSONObject("arguments")?.optString("path").orEmpty()
                if (p.isNotEmpty()) when (c.optString("name")) {
                    "read" -> read += p
                    "write", "edit" -> modified += p
                }
            }
        }
        return Preparation(firstKeptId, toSummarize, prefix, split, tokensBefore, previousSummary, read - modified, modified)
    }

    suspend fun compact(p: Preparation, llm: LlmClient, s: Settings, customInstructions: String? = null): Result {
        var summary = if (p.isSplitTurn && p.turnPrefixMessages.isNotEmpty()) {
            val history = if (p.messagesToSummarize.isNotEmpty()) summarize(p.messagesToSummarize, llm, s, customInstructions, p.previousSummary) else "No prior history."
            val prefix = summarizeTurnPrefix(p.turnPrefixMessages, llm, s)
            "$history\n\n---\n\n**Turn Context (split turn):**\n\n$prefix"
        } else {
            summarize(p.messagesToSummarize, llm, s, customInstructions, p.previousSummary)
        }
        val sections = buildList {
            if (p.readFiles.isNotEmpty()) add("<read-files>\n${p.readFiles.sorted().joinToString("\n")}\n</read-files>")
            if (p.modifiedFiles.isNotEmpty()) add("<modified-files>\n${p.modifiedFiles.sorted().joinToString("\n")}\n</modified-files>")
        }
        if (sections.isNotEmpty()) summary += "\n\n" + sections.joinToString("\n\n")
        val details = JSONObject().put("readFiles", JSONArray(p.readFiles.sorted())).put("modifiedFiles", JSONArray(p.modifiedFiles.sorted()))
        return Result(summary, p.firstKeptEntryId, p.tokensBefore, details)
    }

    private suspend fun summarize(messages: List<JSONObject>, llm: LlmClient, s: Settings, custom: String?, previous: String?): String {
        var base = if (previous != null) UPDATE_PROMPT else PROMPT
        if (!custom.isNullOrBlank()) base += "\n\nAdditional focus: $custom"
        var text = "<conversation>\n${serialize(messages)}\n</conversation>\n\n"
        if (previous != null) text += "<previous-summary>\n$previous\n</previous-summary>\n\n"
        return request(text + base, llm, (0.8 * s.reserveTokens).toInt())
    }

    private suspend fun summarizeTurnPrefix(messages: List<JSONObject>, llm: LlmClient, s: Settings) =
        request("<conversation>\n${serialize(messages)}\n</conversation>\n\n$TURN_PREFIX_PROMPT", llm, (0.5 * s.reserveTokens).toInt())

    private suspend fun request(prompt: String, llm: LlmClient, maxTokens: Int): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", prompt))
        return llm.complete(msgs, maxTokens = maxTokens).message.optString("content").trim()
    }

    /** Labelled text, so the model summarizes instead of continuing; tool results cut to 2000 characters. */
    fun serialize(messages: List<JSONObject>): String = messages.flatMap { m ->
        when (m.optString("role")) {
            "user" -> listOfNotNull(Messages.text(m).takeIf { it.isNotEmpty() }?.let { "[User]: $it" })
            "compactionSummary" -> listOf("[User]: ${Messages.COMPACTION_SUMMARY_PREFIX}${m.optString("summary")}${Messages.COMPACTION_SUMMARY_SUFFIX}")
            "assistant" -> {
                val b = m.optJSONArray("content").blocks()
                buildList {
                    b.filter { it.optString("type") == "thinking" }.map { it.optString("thinking") }.takeIf { it.isNotEmpty() }
                        ?.let { add("[Assistant thinking]: ${it.joinToString("\n")}") }
                    b.filter { it.optString("type") == "text" }.map { it.optString("text") }.takeIf { it.isNotEmpty() }
                        ?.let { add("[Assistant]: ${it.joinToString("\n")}") }
                    b.filter { it.optString("type") == "toolCall" }.map { c ->
                        val args = c.optJSONObject("arguments") ?: JSONObject()
                        "${c.optString("name")}(${args.keys().asSequence().joinToString(", ") { k -> "$k=${JSONObject.wrap(args.get(k)).let { v -> if (v is String) JSONObject.quote(v) else v.toString() }}" }})"
                    }.takeIf { it.isNotEmpty() }?.let { add("[Assistant tool calls]: ${it.joinToString("; ")}") }
                }
            }
            "toolResult" -> listOfNotNull(Messages.text(m).takeIf { it.isNotEmpty() }?.let { "[Tool result]: ${truncate(it, 2000)}" })
            else -> emptyList()
        }
    }.joinToString("\n\n")

    private fun truncate(t: String, max: Int) = if (t.length <= max) t else "${t.take(max)}\n\n[... ${t.length - max} more characters truncated]"

    const val SYSTEM_PROMPT = "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant " +
        "that operates the user's phone, then produce a structured summary following the exact format specified.\n\n" +
        "Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."

    private val PROMPT = """
        The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

        Use this EXACT format:

        ## Goal
        [What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

        ## Constraints & Preferences
        - [Any constraints, preferences, or requirements mentioned by user]
        - [Or "(none)" if none were mentioned]

        ## Progress
        ### Done
        - [x] [Completed tasks/changes]

        ### In Progress
        - [ ] [Current work]

        ### Blocked
        - [Issues preventing progress, if any]

        ## Key Decisions
        - **[Decision]**: [Brief rationale]

        ## Next Steps
        1. [Ordered list of what should happen next]

        ## Critical Context
        - [Any data, examples, or references needed to continue]
        - [Or "(none)" if not applicable]

        Keep each section concise. Preserve exact app names, package names, screen names, contact names and error messages.
    """.trimIndent()

    private val UPDATE_PROMPT = """
        The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

        Update the existing structured summary with new information. RULES:
        - PRESERVE all existing information from the previous summary
        - ADD new progress, decisions, and context from the new messages
        - UPDATE the Progress section: move items from "In Progress" to "Done" when completed
        - UPDATE "Next Steps" based on what was accomplished
        - PRESERVE exact app names, package names, screen names, contact names and error messages
        - If something is no longer relevant, you may remove it

        Use this EXACT format:

        ## Goal
        [Preserve existing goals, add new ones if the task expanded]

        ## Constraints & Preferences
        - [Preserve existing, add new ones discovered]

        ## Progress
        ### Done
        - [x] [Include previously done items AND newly completed items]

        ### In Progress
        - [ ] [Current work - update based on progress]

        ### Blocked
        - [Current blockers - remove if resolved]

        ## Key Decisions
        - **[Decision]**: [Brief rationale] (preserve all previous, add new)

        ## Next Steps
        1. [Update based on current state]

        ## Critical Context
        - [Preserve important context, add new if needed]

        Keep each section concise. Preserve exact app names, package names, screen names, contact names and error messages.
    """.trimIndent()

    private val TURN_PREFIX_PROMPT = """
        This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.

        Summarize the prefix to provide context for the retained suffix:

        ## Original Request
        [What did the user ask for in this turn?]

        ## Early Progress
        - [Key decisions and work done in the prefix]

        ## Context for Suffix
        - [Information needed to understand the retained recent work]

        Be concise. Focus on what's needed to understand the kept suffix.
    """.trimIndent()
}
