package dev.miubridge.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Agent messages in pi's shape (pi-ai / pi-coding-agent), kept as JSON so sessions use pi's file format:
 * - user:        {role, content: string | [text|image blocks], timestamp}
 * - assistant:   {role, content: [text|thinking|toolCall blocks], provider, model, usage, stopReason, timestamp}
 * - toolResult:  {role, toolCallId, toolName, content: [text|image blocks], isError, timestamp}
 * - compactionSummary: {role, summary, tokensBefore, timestamp}
 */
object Messages {
    const val COMPACTION_SUMMARY_PREFIX = "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
    const val COMPACTION_SUMMARY_SUFFIX = "\n</summary>"

    fun user(text: String) = JSONObject().put("role", "user").put("content", text).put("timestamp", now())

    fun toolResult(id: String, name: String, text: String, images: List<String>, isError: Boolean): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        images.forEach { content.put(JSONObject().put("type", "image").put("data", it).put("mimeType", "image/jpeg")) }
        return JSONObject().put("role", "toolResult").put("toolCallId", id).put("toolName", name)
            .put("content", content).put("isError", isError).put("timestamp", now())
    }

    fun compactionSummary(summary: String, tokensBefore: Int, timestamp: Long) = JSONObject()
        .put("role", "compactionSummary").put("summary", summary).put("tokensBefore", tokensBefore).put("timestamp", timestamp)

    /** An assistant message from an OpenAI-style completion. */
    fun assistant(api: JSONObject, finishReason: String?, usage: JSONObject?, model: String): JSONObject {
        val content = JSONArray()
        api.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { content.put(JSONObject().put("type", "thinking").put("thinking", it)) }
        api.optString("content").takeIf { it.isNotEmpty() }?.let { content.put(JSONObject().put("type", "text").put("text", it)) }
        val calls = api.optJSONArray("tool_calls")
        if (calls != null) for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val fn = c.getJSONObject("function")
            val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
            content.put(JSONObject().put("type", "toolCall").put("id", c.getString("id")).put("name", fn.getString("name")).put("arguments", args))
        }
        val stop = when {
            content.blocks().any { it.optString("type") == "toolCall" } -> "toolUse"
            finishReason == "length" -> "length"
            else -> "stop"
        }
        return JSONObject().put("role", "assistant").put("content", content).put("api", "openai-completions")
            .put("provider", "deepseek").put("model", model).put("usage", piUsage(usage)).put("stopReason", stop).put("timestamp", now())
    }

    fun error(message: String, model: String, aborted: Boolean = false) = JSONObject().put("role", "assistant")
        .put("content", JSONArray()).put("provider", "deepseek").put("model", model)
        .put("stopReason", if (aborted) "aborted" else "error").put("errorMessage", message).put("timestamp", now())

    private fun piUsage(u: JSONObject?): JSONObject {
        val input = u?.optInt("prompt_tokens") ?: 0
        val output = u?.optInt("completion_tokens") ?: 0
        val cacheRead = u?.optInt("prompt_cache_hit_tokens") ?: 0
        return JSONObject().put("input", input - cacheRead).put("output", output).put("cacheRead", cacheRead)
            .put("cacheWrite", 0).put("totalTokens", input + output)
    }

    fun toolCalls(assistant: JSONObject): List<JSONObject> =
        assistant.optJSONArray("content").blocks().filter { it.optString("type") == "toolCall" }

    fun text(message: JSONObject): String = when (val c = message.opt("content")) {
        is String -> c
        is JSONArray -> c.blocks().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    /** pi's chars/4 estimate; an image counts as 4800 characters. */
    fun estimateTokens(m: JSONObject): Int {
        var chars = 0
        when (m.optString("role")) {
            "user" -> when (val c = m.opt("content")) {
                is String -> chars = c.length
                is JSONArray -> c.blocks().forEach { if (it.optString("type") == "text") chars += it.optString("text").length }
            }
            "assistant" -> m.optJSONArray("content").blocks().forEach {
                when (it.optString("type")) {
                    "text" -> chars += it.optString("text").length
                    "thinking" -> chars += it.optString("thinking").length
                    "toolCall" -> chars += it.optString("name").length + it.optJSONObject("arguments").toString().length
                }
            }
            "toolResult" -> m.optJSONArray("content").blocks().forEach {
                if (it.optString("type") == "text") chars += it.optString("text").length
                if (it.optString("type") == "image") chars += 4800
            }
            "compactionSummary" -> chars = m.optString("summary").length
        }
        return ceil(chars / 4.0).toInt()
    }

    /**
     * Converts agent messages to OpenAI chat messages. Tool results carry text only on this API, so their
     * images follow in a user message, as pi-ai does for providers without image tool results.
     */
    fun toLlm(systemPrompt: String, messages: List<JSONObject>): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        val pendingImages = mutableListOf<String>()
        val openCalls = LinkedHashSet<String>()
        // A tool call without a result (the run was stopped mid-tool) gets a placeholder, or the API rejects the history.
        fun closeCalls() {
            openCalls.forEach { out.put(JSONObject().put("role", "tool").put("tool_call_id", it).put("content", "No result provided")) }
            openCalls.clear()
        }
        fun flushImages() {
            if (pendingImages.isEmpty()) return
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "Attached image(s) from tool result:"))
            pendingImages.forEach { parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
            out.put(JSONObject().put("role", "user").put("content", parts))
            pendingImages.clear()
        }
        for (m in messages) {
            if (m.optString("role") != "toolResult") { closeCalls(); flushImages() }
            when (m.optString("role")) {
                "user" -> {
                    val c = m.opt("content")
                    if (c is String) out.put(JSONObject().put("role", "user").put("content", c))
                    else out.put(JSONObject().put("role", "user").put("content", text(m)))
                }
                "compactionSummary" -> out.put(JSONObject().put("role", "user")
                    .put("content", COMPACTION_SUMMARY_PREFIX + m.optString("summary") + COMPACTION_SUMMARY_SUFFIX))
                "assistant" -> {
                    if (m.optString("stopReason") == "error" || m.optString("stopReason") == "aborted") continue
                    val blocks = m.optJSONArray("content").blocks()
                    val msg = JSONObject().put("role", "assistant")
                        .put("content", blocks.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") })
                    // DeepSeek requires the reasoning content back on assistant messages.
                    msg.put("reasoning_content", blocks.filter { it.optString("type") == "thinking" }.joinToString("\n") { it.optString("thinking") })
                    val calls = blocks.filter { it.optString("type") == "toolCall" }
                    calls.forEach { openCalls += it.getString("id") }
                    if (calls.isNotEmpty()) msg.put("tool_calls", JSONArray().apply {
                        calls.forEach { c ->
                            put(JSONObject().put("id", c.getString("id")).put("type", "function").put("function",
                                JSONObject().put("name", c.getString("name")).put("arguments", c.optJSONObject("arguments")?.toString() ?: "{}")))
                        }
                    })
                    out.put(msg)
                }
                "toolResult" -> {
                    if (!openCalls.remove(m.getString("toolCallId"))) continue
                    out.put(JSONObject().put("role", "tool").put("tool_call_id", m.getString("toolCallId")).put("content", text(m)))
                    m.optJSONArray("content").blocks().filter { it.optString("type") == "image" }.forEach { pendingImages += it.getString("data") }
                }
            }
        }
        closeCalls()
        flushImages()
        return out
    }

    fun now() = System.currentTimeMillis()
}

fun JSONArray?.blocks(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
