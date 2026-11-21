package dev.miubridge.server

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import dev.miubridge.core.CommandHandler
import dev.miubridge.core.CommandResult
import dev.miubridge.core.CommandSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A minimal MCP server over Streamable HTTP (JSON responses, no SSE): `POST /mcp` with JSON-RPC, served on the
 * local network and, through [RelayClient], from the public relay.
 * Every request needs `Authorization: Bearer <token>`. Tool calls run one at a time, because the
 * phone has one background screen.
 */
class McpServer(
    private val handler: CommandHandler,
    private val token: AccessToken,
    private val port: Int = DEFAULT_PORT,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callLock = Mutex()

    /**
     * Answers already given, by the caller's `request_id`. A caller whose request timed out on the way
     * cannot know whether the tap happened; sending it again with the same id gets the first answer
     * back instead of a second tap.
     */
    private val answered = object : LinkedHashMap<String, Pair<Long, JSONObject>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>) = size > REMEMBERED
    }
    private var socket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            // The OS can close our sockets under us (HarmonyOS power management does): listen again.
            while (isActive) {
                val server = try {
                    ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                } catch (e: Exception) {
                    delay(2_000)
                    continue
                }
                socket = server
                while (isActive) {
                    val client = try { server.accept() } catch (e: Exception) { break }
                    launch { serve(client) }
                }
                runCatching { server.close() }
                if (isActive) delay(1_000)
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun serve(client: Socket) = client.use { s ->
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        while (true) {
            val request = try { readRequest(input) } catch (e: SocketTimeoutException) { null } catch (e: Exception) { null } ?: return
            val keepAlive = !request.headers["connection"].equals("close", ignoreCase = true)
            respond(request, out)
            if (!keepAlive) return
        }
    }

    private suspend fun respond(req: HttpRequest, out: OutputStream) {
        if (req.path.substringBefore('?') != "/mcp") return write(out, 404, json("error" to "not found"))
        val reply = handle(req.method, req.headers["authorization"], req.body)
        write(out, reply.status, reply.body, reply.ms)
    }

    /** [ms] is how long this phone took over the request, for whoever is tracing where the time goes. */
    class Reply(val status: Int, val body: JSONObject?, val ms: Long = 0)

    /** One MCP request, from the local HTTP server or from the relay. Checks the bearer token itself. */
    suspend fun handle(method: String, authorization: String?, rawBody: ByteArray): Reply {
        val start = SystemClock.elapsedRealtime()
        val reply = dev.miubridge.core.Awake.during { answer(method, authorization, rawBody) }
        return Reply(reply.status, reply.body, SystemClock.elapsedRealtime() - start)
    }

    private suspend fun answer(method: String, authorization: String?, rawBody: ByteArray): Reply {
        if (!token.matches(authorization)) return Reply(401, json("error" to "unauthorized"))
        when (method) {
            "POST" -> {}
            "DELETE" -> return Reply(200, null)
            else -> return Reply(405, null) // no server-initiated stream
        }
        val body = runCatching { JSONObject(String(rawBody, Charsets.UTF_8)) }.getOrNull()
            ?: return Reply(400, rpcError(null, -32700, "parse error"))
        if (!body.has("id")) return Reply(202, null) // a notification
        val id = body.get("id")
        val params = body.optJSONObject("params") ?: JSONObject()
        val reply = when (body.optString("method")) {
            "initialize" -> rpcResult(id, JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifEmpty { PROTOCOL_VERSION })
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "miu-bridge").put("version", "0.1.0"))
                .put("instructions", handler.guideMarkdown()))
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(id, JSONObject().put("tools", JSONArray().apply {
                handler.specs().forEach { (name, spec) -> put(toolJson(name, spec)) }
            }))
            "tools/call" -> rpcResult(id, callTool(params))
            else -> rpcError(id, -32601, "method not found")
        }
        return Reply(200, reply)
    }

    private suspend fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val spec = handler.specs()[name] ?: return toolError("unknown tool: $name", "unknown_tool")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val requestId = args.optString("request_id").takeIf { it.isNotBlank() && it.length <= 100 }?.let { "$name:$it" }
        args.remove("request_id")
        spec.required.keys.firstOrNull { !args.has(it) }?.let { return toolError("missing argument: $it", "bad_request") }
        // One call at a time: a repeat of a call still running waits here, then finds its answer.
        val asked = SystemClock.elapsedRealtime()
        return callLock.withLock {
            val now = SystemClock.elapsedRealtime()
            requestId?.let { answered[it] }?.takeIf { now - it.first < REMEMBER_MS }?.let {
                Log.i(TAG, "tool $name repeated; first answer returned")
                return@withLock JSONObject(it.second.toString()).put("_meta", JSONObject().put("replayed", true))
            }
            dev.miubridge.core.CallTiming.begin()
            val result = withTimeoutOrNull(spec.timeoutMs) {
                runCatching { handler.invoke(name, args) }.getOrElse {
                    Log.w(TAG, "$name failed: ${it.javaClass.simpleName}")
                    CommandResult.Error("internal error", "internal")
                }
            } ?: CommandResult.Error("timed out after ${spec.timeoutMs} ms; the action may or may not have happened, take a phone_snapshot before retrying", "timeout")
            Log.i(TAG, "tool $name ${if (result is CommandResult.Ok) "ok" else "error ${(result as CommandResult.Error).code}"}")
            val answer = when (result) {
                is CommandResult.Error -> toolError(result.message, result.code)
                is CommandResult.Ok -> JSONObject().put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", result.payload.toString()))
                    result.images.forEach {
                        put(JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(it, Base64.NO_WRAP)))
                    }
                })
            }
            if (requestId != null) answered[requestId] = now to answer
            // Durations only: how long it waited for the call before it, how long the tool took, and the tool's own parts.
            val timing = dev.miubridge.core.CallTiming.into(JSONObject().put("queued_ms", now - asked).put("tool_ms", SystemClock.elapsedRealtime() - now))
            JSONObject(answer.toString()).put("_meta", JSONObject().put("timing", timing))
        }
    }

    private fun toolJson(name: String, spec: CommandSpec): JSONObject {
        val props = JSONObject()
        (spec.required + spec.optional).forEach { (k, p) ->
            props.put(k, JSONObject().put("type", p.type).put("description", p.description))
        }
        return JSONObject().put("name", name).put("description", spec.description).put("inputSchema",
            JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(spec.required.keys.toList())))
    }

    /** The code rides in the text for a model to read and in `structuredContent` for a program to branch on. */
    private fun toolError(message: String, code: String) = JSONObject().put("isError", true)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "$message [code: $code]")))
        .put("structuredContent", JSONObject().put("code", code).put("message", message))

    private fun rpcResult(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    private fun rpcError(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))
    private fun json(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    private fun write(out: OutputStream, status: Int, body: JSONObject?, ms: Long = 0) {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 $status ${REASONS[status] ?: "OK"}\r\n")
            if (body != null) append("Content-Type: application/json\r\n")
            append("Server-Timing: phone;dur=$ms\r\n")
            append("Content-Length: ${bytes.size}\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 100) return null
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return HttpRequest(parts[0], parts[1], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8192) return null
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_BODY = 1 shl 20
        private const val REMEMBERED = 64
        private const val REMEMBER_MS = 10 * 60_000L
        private const val TAG = "McpServer"
        private val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed")
    }
}
