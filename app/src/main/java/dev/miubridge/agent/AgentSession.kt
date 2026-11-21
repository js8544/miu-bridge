package dev.miubridge.agent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.miubridge.AgentActivity
import dev.miubridge.App
import dev.miubridge.R
import dev.miubridge.BridgeService
import dev.miubridge.core.CommandResult
import dev.miubridge.core.CommandSpec
import dev.miubridge.core.ScriptRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * pi's AgentSession for the phone: one session file, one agent run at a time, steering while it runs,
 * retry with backoff on transient errors, and compaction after a run (on threshold) or to recover from a
 * context overflow (compact, then retry once). The single owner of the session and of what the screen shows.
 */
class AgentSession(private val app: App) {
    enum class Kind { USER, AGENT, THINKING, TOOL, TOOL_ERROR, ERROR, STOPPED, COMPACTED }

    /** One thing on the screen. For [Kind.TOOL], [text] is the tool name and [detail] its arguments. */
    data class Entry(val kind: Kind, val text: String, val detail: String = "")

    /** [live] is the model's answer while it streams: its thinking, its text, the tools it is calling. */
    data class State(
        val running: Boolean = false, val status: String = "", val lines: List<Entry> = emptyList(), val live: List<Entry> = emptyList(),
        /** Messages the owner sent while it was working, waiting for the run to finish. */
        val queued: List<String> = emptyList(),
    )

    /** Which model service the agent thinks with. Keys are kept per provider, on this phone only. */
    data class ModelConfig(
        val provider: LlmClient.Provider, val baseUrl: String, val model: String, val apiKey: String,
        /** OpenAI-style reasoning effort: "", "low", "medium" or "high". Empty leaves it to the model. */
        val effort: String = "",
    ) {
        val ready: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = app.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private val sessionsDir = File(app.filesDir, "sessions")
    private val workspace = Workspace(app, app.skills)
    private val settings = Compaction.Settings()
    private val steering = ArrayList<JSONObject>()
    private val followUps = ArrayList<JSONObject>()
    private var session: Session = Session.latest(sessionsDir) ?: Session.create(sessionsDir)
    private var job: Job? = null
    private var overflowRecoveryAttempted = false
    private val _state = MutableStateFlow(State(lines = render()))
    val state: StateFlow<State> = _state

    /** The saved settings for [providerId] (the selected provider by default). */
    fun config(providerId: String? = null): ModelConfig {
        val provider = LlmClient.provider(providerId ?: prefs.getString("provider", null))
        val legacyKey = if (provider.id == "deepseek") prefs.getString("api_key", "").orEmpty() else ""
        return ModelConfig(
            provider,
            baseUrl = if (provider.id == LlmClient.CUSTOM) prefs.getString("base_url", "").orEmpty() else provider.baseUrl,
            model = prefs.getString("model_${provider.id}", null) ?: provider.defaultModel,
            apiKey = prefs.getString("key_${provider.id}", null) ?: legacyKey,
            effort = prefs.getString("effort_${provider.id}", "").orEmpty(),
        )
    }

    /** Saves [config] and makes its provider the selected one. */
    fun save(config: ModelConfig) {
        prefs.edit()
            .putString("provider", config.provider.id)
            .putString("key_${config.provider.id}", config.apiKey.trim())
            .putString("model_${config.provider.id}", config.model.trim())
            .putString("effort_${config.provider.id}", config.effort)
            .apply { if (config.provider.id == LlmClient.CUSTOM) putString("base_url", config.baseUrl.trim().trimEnd('/')) }
            .apply()
    }

    fun client(config: ModelConfig) = LlmClient(config.apiKey, LlmClient.Model(config.model, config.provider.id, config.baseUrl, config.provider.contextWindow), config.effort)

    /** A message from the owner: starts a run, or steers the one in progress. */
    @Synchronized
    fun prompt(text: String) {
        val t = text.trim().ifEmpty { return }
        if (job?.isActive == true) {
            synchronized(steering) { steering += Messages.user(t) }
            setStatus(app.getString(R.string.agent_steer_received))
            return
        }
        if (!config().ready) { setStatus(app.getString(R.string.agent_need_model)); return }
        overflowRecoveryAttempted = false
        BridgeService.start(app)
        synchronized(steering) { steering += Messages.user(t) }
        start()
    }

    /** A message to handle after the current run would stop (pi's follow-up). */
    fun followUp(text: String) {
        if (job?.isActive == true) {
            synchronized(followUps) { followUps += Messages.user(text.trim()) }
            publishQueue()
        } else {
            prompt(text)
        }
    }

    /** Moves a queued message to the front: the agent reads it before its next step instead of after the run. */
    fun insertNow(index: Int) {
        val message = synchronized(followUps) { followUps.getOrNull(index)?.also { followUps.removeAt(index) } } ?: return
        publishQueue()
        if (job?.isActive == true) synchronized(steering) { steering += message } else prompt(Messages.text(message))
    }

    /** Takes a queued message back before the agent has seen it. */
    fun dropQueued(index: Int) {
        synchronized(followUps) { if (index in followUps.indices) followUps.removeAt(index) }
        publishQueue()
    }

    private fun publishQueue() {
        val texts = synchronized(followUps) { followUps.map { Messages.text(it) } }
        _state.update { it.copy(queued = texts) }
    }

    fun abort() { job?.cancel() }

    @Synchronized
    fun newSession() {
        if (job?.isActive == true) return
        session = Session.create(sessionsDir)
        refresh(app.getString(R.string.agent_new_session))
    }

    /** Manual /compact. */
    fun compact(instructions: String? = null) {
        if (job?.isActive == true) return
        job = scope.launch { runCompaction("manual", instructions, retry = false) }
    }

    private fun start() {
        job = scope.launch {
            _state.update { it.copy(running = true) }
            try {
                // The service no longer holds the CPU awake by itself: a run on this phone must, or it stalls when the screen goes off.
                dev.miubridge.core.Awake.during { runAgent() }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    session.appendMessage(Messages.error("aborted by the owner", llm().model.id, aborted = true))
                    refresh(app.getString(R.string.agent_stopped))
                }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** One agent run, then pi's post-run handling: retry transient errors, then check for compaction. */
    private suspend fun runAgent() {
        var attempt = 0
        while (true) {
            val llm = llm()
            val context = Session.buildContext(session.branch()).toMutableList()
            setStatus("")
            val last = AgentLoop(
                llm, tools(), ::systemPrompt,
                takeSteering = { synchronized(steering) { steering.toList().also { steering.clear() } } },
                takeFollowUps = { synchronized(followUps) { followUps.toList().also { followUps.clear() } }.also { publishQueue() } },
                onMessage = { m -> session.appendMessage(m); _state.update { it.copy(live = emptyList()) }; refresh() },
                onToolStart = { name, _ -> setStatus(name) },
                onStreaming = { thinking, text, toolNames -> _state.update { it.copy(live = liveEntries(thinking, text, toolNames)) } },
            ).run(context) ?: return

            if (last.optString("stopReason") == "error" && isRetryable(last) && attempt < MAX_RETRIES) {
                val wait = BASE_DELAY_MS shl attempt
                attempt++
                setStatus(app.getString(R.string.agent_retrying, (wait / 1000).toInt(), attempt))
                delay(wait)
                continue
            }
            if (checkCompaction(last)) continue // overflow: compacted, retry the run
            notifyDone(last)
            return
        }
    }

    /** Returns true when it compacted after a context overflow and the run should be retried. */
    private suspend fun checkCompaction(last: JSONObject): Boolean {
        if (!settings.enabled || last.optString("stopReason") == "aborted") return false
        val window = llm().model.contextWindow
        if (last.optString("stopReason") == "error" && isOverflow(last)) {
            if (overflowRecoveryAttempted) {
                setStatus(app.getString(R.string.agent_overflow))
                return false
            }
            overflowRecoveryAttempted = true
            return runCompaction("overflow", null, retry = true)
        }
        val tokens = if (last.optString("stopReason") == "error") {
            Compaction.estimateContextTokens(Session.buildContext(session.branch()))
        } else {
            last.optJSONObject("usage")?.optInt("totalTokens") ?: 0
        }
        if (Compaction.shouldCompact(tokens, window, settings)) runCompaction("threshold", null, retry = false)
        return false
    }

    private suspend fun runCompaction(reason: String, instructions: String?, retry: Boolean): Boolean {
        val prep = Compaction.prepare(session.branch(), settings) ?: return false
        setStatus(app.getString(R.string.agent_compacting))
        return try {
            val r = Compaction.compact(prep, llm(), settings, instructions)
            session.appendCompaction(r.summary, r.firstKeptEntryId, r.tokensBefore, r.details)
            refresh(app.getString(R.string.agent_compacted))
            retry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(app.getString(R.string.agent_compact_failed))
            false
        }
    }

    private fun isRetryable(m: JSONObject): Boolean {
        val msg = m.optString("errorMessage")
        if (isOverflow(m)) return false
        return Regex("^(429|500|502|503|504):").containsMatchIn(msg) ||
            m.optString("errorClass") in setOf("SocketTimeoutException", "UnknownHostException", "ConnectException", "IOException", "SSLException")
    }

    private fun isOverflow(m: JSONObject) =
        Regex("context length|context window|maximum context|too many tokens|too long", RegexOption.IGNORE_CASE).containsMatchIn(m.optString("errorMessage"))

    private fun llm() = client(config())

    private fun systemPrompt(): String {
        val tools = (app.phone.specs() + (ScriptRunner.TOOL to ScriptRunner.SPEC) + workspace.specs).entries.joinToString("\n") { (n, s) -> "- $n: ${s.description.substringBefore(". ")}" }
        val sb = StringBuilder()
        sb.append("""
            You are an assistant operating inside Miu Bridge, an agent harness on the owner's Android phone. You help the owner by using apps on a background display that they cannot see, and by reading and writing files in your workspace.

            Available tools:
            $tools

            Guidelines:
            - Work only on the background display; the owner keeps using the main screen
            - Read a snapshot before acting; act by ref when the tree has one, otherwise take a screenshot
            - Prefer phone_script: do every step you can already plan in one script, and stop to look only when the result changes what comes next (see Scripts below)
            - bash runs Android's sh inside your workspace: use it to search, sort or transform files there, not to control apps
            - Be concise in your responses
            - When you need the owner (a decision, a confirmation, a login), stop and ask in your reply; they will answer in the next message
            - Read an app's skill before using the app. When you learn a fact about an app that would have saved you steps, append it as one "- " line to skills/<name>/LEARNED.md (facts only, nothing private); when a line there turns out wrong, edit or delete it
        """.trimIndent())
        sb.append("\n\n").append(app.phone.guideMarkdown()).append("\n\n").append(ScriptRunner.GUIDE)
        workspace.agentsMd()?.let { sb.append("\n\n# Project Context\n\nProject-specific instructions and guidelines:\n\n## AGENTS.md\n\n").append(it).append("\n\n") }
        val skills = workspace.skills()
        if (skills.isNotEmpty()) {
            sb.append("\n\nThe following skills provide specialized instructions for specific tasks.\n")
            sb.append("Use the read tool to load a skill's file when the task matches its description.\n\n<available_skills>\n")
            skills.forEach { s ->
                sb.append("  <skill>\n    <name>${xml(s.name)}</name>\n    <description>${xml(s.description)}</description>\n    <location>${xml(s.path)}</location>\n  </skill>\n")
            }
            sb.append("</available_skills>")
        }
        sb.append("\nCurrent date: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        sb.append("\nCurrent working directory: workspace")
        return sb.toString()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun tools() = object : AgentLoop.Tools {
        // The phone tools, the script tool that chains them, and the workspace tools.
        val specs: Map<String, CommandSpec> = app.phone.specs() + (ScriptRunner.TOOL to ScriptRunner.SPEC) + workspace.specs

        override fun json(): JSONArray = JSONArray().apply {
            specs.forEach { (name, spec) ->
                val props = JSONObject()
                (spec.required + spec.optional).forEach { (k, p) -> props.put(k, JSONObject().put("type", p.type).put("description", p.description)) }
                put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", spec.description)
                    .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(spec.required.keys.toList())))))
            }
        }

        override suspend fun execute(name: String, args: JSONObject): AgentLoop.Output = try {
            when (name) {
                "read" -> AgentLoop.Output(workspace.read(args.getString("path"), args.optInt("offset").takeIf { it > 0 }, args.optInt("limit").takeIf { it > 0 }))
                "write" -> AgentLoop.Output(workspace.write(args.getString("path"), args.getString("content")))
                "edit" -> AgentLoop.Output(workspace.edit(args.getString("path"), args.getString("oldText"), args.getString("newText")))
                "bash" -> AgentLoop.Output(withContext(Dispatchers.IO) { workspace.bash(args.getString("command")) })
                ScriptRunner.TOOL -> when (val r = app.tools.invoke(name, args)) {
                    is CommandResult.Ok -> AgentLoop.Output(r.payload.optString("output"), r.images.map { Base64.encodeToString(it, Base64.NO_WRAP) })
                    is CommandResult.Error -> AgentLoop.Output(r.message, isError = true)
                }
                in app.phone.specs() -> when (val r = app.phone.invoke(name, args)) {
                    is CommandResult.Ok -> AgentLoop.Output(r.payload.toString(), r.images.map { Base64.encodeToString(it, Base64.NO_WRAP) })
                    is CommandResult.Error -> AgentLoop.Output(r.message, isError = true)
                }
                else -> AgentLoop.Output("unknown tool $name", isError = true)
            }
        } catch (e: org.json.JSONException) {
            AgentLoop.Output("missing or bad argument: ${e.message}", isError = true)
        } catch (e: IllegalArgumentException) {
            AgentLoop.Output(e.message ?: "bad arguments", isError = true)
        }
    }

    private fun setStatus(s: String) = _state.update { it.copy(status = s) }

    private fun refresh(status: String? = null) = _state.update { it.copy(lines = render(), status = status ?: it.status) }

    /** What the screen shows: the active branch, compacted parts as a marker. */
    private fun render(): List<Entry> = session.branch().flatMap { e ->
        when (e.optString("type")) {
            "compaction" -> listOf(Entry(Kind.COMPACTED, ""))
            "message" -> {
                val m = e.getJSONObject("message")
                when (m.optString("role")) {
                    "user" -> listOf(Entry(Kind.USER, Messages.text(m)))
                    "assistant" -> buildList {
                        m.optJSONArray("content").blocks().filter { it.optString("type") == "thinking" }
                            .joinToString("\n") { it.optString("thinking") }.takeIf { it.isNotBlank() }?.let { add(Entry(Kind.THINKING, it.trim())) }
                        Messages.text(m).takeIf { it.isNotBlank() }?.let { add(Entry(Kind.AGENT, it)) }
                        Messages.toolCalls(m).forEach { add(Entry(Kind.TOOL, it.optString("name"), it.optJSONObject("arguments")?.toString()?.take(160).orEmpty())) }
                        when (m.optString("stopReason")) {
                            "error" -> add(Entry(Kind.ERROR, m.optString("errorMessage")))
                            "aborted" -> add(Entry(Kind.STOPPED, ""))
                        }
                    }
                    "toolResult" -> if (m.optBoolean("isError")) listOf(Entry(Kind.TOOL_ERROR, Messages.text(m).take(200))) else emptyList()
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }.takeLast(MAX_LINES)

    private fun liveEntries(thinking: String, text: String, toolNames: List<String>) = buildList {
        if (thinking.isNotBlank()) add(Entry(Kind.THINKING, thinking.trim()))
        if (text.isNotBlank()) add(Entry(Kind.AGENT, text))
        toolNames.filter { it.isNotEmpty() }.forEach { add(Entry(Kind.TOOL, it)) }
    }

    private fun notifyDone(last: JSONObject) {
        val text = when (last.optString("stopReason")) {
            "error" -> app.getString(R.string.agent_notify_error)
            else -> Messages.text(last).ifBlank { app.getString(R.string.agent_notify_done) }
        }
        val open = PendingIntent.getActivity(app, 2, Intent(app, AgentActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(app.getString(R.string.agent_notify_reply_hint)).build()
        val reply = PendingIntent.getBroadcast(app, 3, Intent(app, ReplyReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(app, App.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(app.getString(R.string.agent_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, app.getString(R.string.agent_notify_reply), reply).addRemoteInput(input).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }

    /** A reply typed into the notification becomes the next prompt (or steering, if a run is going). */
    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
            if (text.isNotEmpty()) (context.applicationContext as App).agent.prompt(text)
        }
    }

    private companion object {
        const val MAX_LINES = 400
        const val MAX_RETRIES = 3
        const val BASE_DELAY_MS = 2_000L
        const val KEY_REPLY = "reply"
        const val NOTIFY_ID = 11
    }
}
