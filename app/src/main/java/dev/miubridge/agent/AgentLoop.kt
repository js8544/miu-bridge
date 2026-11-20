package dev.miubridge.agent

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * pi-agent-core's loop. The inner loop calls the model and runs its tool calls; after each turn it takes any
 * steering messages the owner typed meanwhile and injects them before the next model call. When the model
 * stops calling tools, queued follow-up messages restart it. Every message goes through [onMessage].
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: Tools,
    private val systemPrompt: () -> String,
    private val takeSteering: () -> List<JSONObject>,
    private val takeFollowUps: () -> List<JSONObject>,
    private val onMessage: (JSONObject) -> Unit,
    private val onToolStart: (String, JSONObject) -> Unit,
    private val onStreaming: (thinking: String, text: String, toolNames: List<String>) -> Unit,
) {
    interface Tools {
        fun json(): JSONArray
        suspend fun execute(name: String, args: JSONObject): Output
    }

    class Output(val text: String, val images: List<String> = emptyList(), val isError: Boolean = false)

    /** Runs until the model stops and nothing is queued. Returns the last assistant message. */
    suspend fun run(context: MutableList<JSONObject>): JSONObject? {
        var pending = takeSteering()
        var last: JSONObject? = null
        while (true) {
            var moreToolCalls = true
            while (moreToolCalls || pending.isNotEmpty()) {
                pending.forEach { context += it; onMessage(it) }
                pending = emptyList()

                val assistant = try {
                    val c = llm.stream(Messages.toLlm(systemPrompt(), context), tools.json(), onStreaming)
                    Messages.assistant(c.message, c.finishReason, c.usage, llm.model.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Messages.error(e.message ?: e.javaClass.simpleName, llm.model.id).also { it.put("errorClass", e.javaClass.simpleName) }
                }
                context += assistant
                onMessage(assistant)
                last = assistant
                if (assistant.optString("stopReason") == "error") return assistant

                val calls = Messages.toolCalls(assistant)
                moreToolCalls = calls.isNotEmpty()
                for (call in calls) {
                    val name = call.getString("name")
                    val args = call.optJSONObject("arguments") ?: JSONObject()
                    onToolStart(name, args)
                    val out = try {
                        tools.execute(name, args)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Output(e.message ?: "tool failed", isError = true)
                    }
                    val result = Messages.toolResult(call.getString("id"), name, out.text, out.images, out.isError)
                    context += result
                    onMessage(result)
                }
                pending = takeSteering()
            }
            val followUps = takeFollowUps()
            if (followUps.isEmpty()) return last
            pending = followUps
        }
    }
}
