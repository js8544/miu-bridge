package dev.miubridge.server

import dev.miubridge.R
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps an outbound WebSocket to the relay so agents in the cloud can reach this phone without a
 * computer or an open port. Each relay message is one MCP request; the answer goes back on the
 * same socket. Reconnects with backoff until stopped.
 */
class RelayClient(private val context: Context, private val server: McpServer?) {
    data class State(val connected: Boolean = false, val message: String = "")

    private val account = Account(context)
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val deviceId = account.deviceId
        val deviceKey = account.deviceKey
        if (!account.signedIn || deviceId == null || deviceKey == null) {
            _state.value = State(false, context.getString(R.string.relay_signed_out))
            return
        }
        val url = account.baseUrl.replaceFirst("http", "ws") + "/d/$deviceId/connect"
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            var backoff = 1_000L
            while (true) {
                val closed = Job()
                val socket = http.newWebSocket(
                    Request.Builder().url(url)
                        .header("X-Device-Key", deviceKey).build(),
                    Listener(s, closed) { backoff = 1_000L },
                )
                closed.join()
                socket.cancel()
                _state.value = State(false, context.getString(R.string.relay_reconnecting, (backoff / 1000).toInt()))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        _state.value = State(false, context.getString(R.string.relay_off))
    }

    private inner class Listener(
        private val scope: CoroutineScope,
        private val closed: Job,
        private val onOpen: () -> Unit,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "relay link open")
            onOpen()
            _state.value = State(true, context.getString(R.string.relay_on))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                val req = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                val reply = (server ?: return@launch).handle(req.optString("method"), req.optString("authorization"), req.optString("body").toByteArray())
                val out = JSONObject().put("id", req.optString("id")).put("status", reply.status).put("ms", reply.ms)
                reply.body?.let { out.put("body", it.toString()) }
                webSocket.send(out.toString())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "relay closed the link: $code $reason")
            webSocket.close(1000, null)
            closed.cancel()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Never the URL or the key: only what kind of failure it was.
            Log.w(TAG, "relay link failed: ${t.javaClass.simpleName} ${response?.code ?: ""}")
            if (response?.code == 403 || response?.code == 404) {
                // The key was replaced or the device removed; retrying cannot help until the owner signs in again.
                scope.cancel()
                _state.value = State(false, context.getString(R.string.relay_expired))
            }
            closed.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.cancel()
        }
    }

    companion object {
        private const val TAG = "RelayClient"
        private val _state = MutableStateFlow(State())
        /** One relay connection per app process; the screen shows this. */
        val state: StateFlow<State> = _state
    }
}
