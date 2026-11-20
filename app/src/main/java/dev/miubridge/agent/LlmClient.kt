package dev.miubridge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-style chat completions, which DeepSeek, OpenAI, OpenRouter and the OpenAI-compatible endpoints
 * of Anthropic and Google Gemini all speak. Cancelling the coroutine cancels the call.
 */
class LlmClient(private val apiKey: String, val model: Model, private val effort: String = "") {
    data class Model(val id: String, val provider: String, val baseUrl: String, val contextWindow: Int)

    /**
     * A model service the owner can pick. [defaultModel] is empty where the current model names are best
     * read from the service itself ([listModels]); [contextWindow] is a safe floor used to decide when to compact.
     */
    data class Provider(val id: String, val name: String, val baseUrl: String, val defaultModel: String, val contextWindow: Int)

    companion object {
        private val JSON = "application/json".toMediaType()
        const val CUSTOM = "custom"

        val PROVIDERS = listOf(
            // pi-ai lists DeepSeek V4 Flash with a 1,000,000-token window; deepseek-flash is its current alias.
            Provider("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", 1_000_000),
            Provider("openai", "OpenAI", "https://api.openai.com/v1", "", 128_000),
            Provider("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5", 200_000),
            Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "", 128_000),
            Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", 128_000),
            Provider(CUSTOM, "OpenAI-compatible", "", "", 128_000),
        )

        fun provider(id: String?): Provider = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()
    }

    /** The model ids this key can use, as the service lists them. */
    suspend fun listModels(): List<String> {
        val text = withContext(Dispatchers.IO) { execute(http.newCall(authorized(Request.Builder().url("${model.baseUrl}/models")).build())) }
        val data = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf(String::isNotEmpty) }.sorted()
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        builder.header("Authorization", "Bearer $apiKey")
        // Anthropic's native routes (the model list) want its own headers; its chat route takes either.
        if (model.provider == "anthropic") builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
        return builder
    }

    class ApiError(val status: Int, message: String) : IOException(message)

    class Completion(val message: JSONObject, val finishReason: String?, val usage: JSONObject?)

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    suspend fun complete(messages: JSONArray, tools: JSONArray? = null, maxTokens: Int? = null): Completion {
        val body = JSONObject().put("model", model.id).put("messages", messages)
        if (tools != null && tools.length() > 0) body.put("tools", tools)
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        if (effort.isNotBlank()) body.put("reasoning_effort", effort)
        val request = authorized(Request.Builder().url("${model.baseUrl}/chat/completions"))
            .post(body.toString().toRequestBody(JSON)).build()
        val text = withContext(Dispatchers.IO) { execute(http.newCall(request)) }
        val json = JSONObject(text)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        return Completion(choice.getJSONObject("message"), choice.optString("finish_reason").ifEmpty { null }, json.optJSONObject("usage"))
    }

    /**
     * Streams a completion (server-sent events) and calls [onDelta] with the thinking and text so far, so the
     * screen shows the model working as it goes, like pi. Returns the same shape as [complete].
     */
    suspend fun stream(messages: JSONArray, tools: JSONArray?, onDelta: (thinking: String, text: String, toolNames: List<String>) -> Unit): Completion {
        val body = JSONObject().put("model", model.id).put("messages", messages).put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        if (tools != null && tools.length() > 0) body.put("tools", tools)
        // Left out unless the owner chose a level: not every provider or model accepts it.
        if (effort.isNotBlank()) body.put("reasoning_effort", effort)
        val call = http.newCall(authorized(Request.Builder().url("${model.baseUrl}/chat/completions")).header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON)).build())
        return withContext(Dispatchers.IO) {
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = response.body?.string().orEmpty()
                        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                        throw ApiError(response.code, "${response.code}: ${msg ?: "request failed"}")
                    }
                    val thinking = StringBuilder()
                    val text = StringBuilder()
                    val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, arguments
                    var finish: String? = null
                    var usage: JSONObject? = null
                    val source = response.body!!.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        chunk.optJSONObject("usage")?.let { usage = it }
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                        val delta = choice.optJSONObject("delta") ?: continue
                        delta.optString("reasoning_content").takeIf { it.isNotEmpty() && !delta.isNull("reasoning_content") }?.let { thinking.append(it) }
                        delta.optString("content").takeIf { it.isNotEmpty() && !delta.isNull("content") }?.let { text.append(it) }
                        delta.optJSONArray("tool_calls")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val tc = arr.getJSONObject(i)
                                val slot = calls.getOrPut(tc.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                tc.optString("id").takeIf { it.isNotEmpty() }?.let { slot.first.setLength(0); slot.first.append(it) }
                                tc.optJSONObject("function")?.let { f ->
                                    f.optString("name").takeIf { it.isNotEmpty() }?.let { slot.second.append(it) }
                                    f.optString("arguments").takeIf { it.isNotEmpty() && !f.isNull("arguments") }?.let { slot.third.append(it) }
                                }
                            }
                        }
                        onDelta(thinking.toString(), text.toString(), calls.values.map { it.second.toString() })
                    }
                    val message = JSONObject().put("role", "assistant").put("content", text.toString())
                    if (thinking.isNotEmpty()) message.put("reasoning_content", thinking.toString())
                    if (calls.isNotEmpty()) message.put("tool_calls", JSONArray().apply {
                        calls.values.forEach { (id, name, args) ->
                            put(JSONObject().put("id", id.toString()).put("type", "function")
                                .put("function", JSONObject().put("name", name.toString()).put("arguments", args.toString())))
                        }
                    })
                    Completion(message, finish, usage)
                }
            } finally {
                handle?.dispose()
            }
        }
    }

    private suspend fun execute(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (it.isSuccessful) cont.resume(text)
                    else {
                        // Error bodies hold a message; providers mask the key in it.
                        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                        cont.resumeWithException(ApiError(it.code, "${it.code}: ${msg ?: "request failed"}"))
                    }
                }
            }
        })
    }
}
