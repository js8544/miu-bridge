package dev.miubridge.server

import android.content.Context
import android.os.Build
import android.util.Log
import dev.miubridge.BuildConfig
import dev.miubridge.core.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The opt-in experience improvement program. When the owner has joined, each tool call leaves one
 * event of metadata (which tool, which app, whether it worked, its error code, how long it took, how
 * the element was named) and batches go to the relay. Nothing the screen showed, nothing typed, no
 * screenshot, URL, account or email is ever part of an event: [event] reads only the fields it names.
 *
 * Events carry a random install id made when the owner joins and dropped when they leave (after
 * asking the relay to forget it), so what was sent cannot be linked to the account or to a later
 * stint in the program.
 */
class Traces(private val context: Context, private val account: Account) {
    private val prefs = context.getSharedPreferences("trace", Context.MODE_PRIVATE)
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = ArrayList<JSONObject>()

    /** Whether the owner has answered the question, either way. */
    val asked: Boolean get() = prefs.contains("enabled")
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    fun start() {
        scope.launch {
            while (true) {
                delay(FLUSH_EVERY_MS)
                flush()
            }
        }
    }

    /** Joins or leaves. Leaving drops what is still buffered and asks the relay to delete what it has. */
    fun setEnabled(on: Boolean) {
        if (on) {
            prefs.edit().putBoolean("enabled", true).putString("install", prefs.getString("install", null) ?: UUID.randomUUID().toString()).apply()
            return
        }
        val install = prefs.getString("install", null)
        prefs.edit().putBoolean("enabled", false).remove("install").apply()
        synchronized(buffer) { buffer.clear() }
        if (install != null) scope.launch {
            runCatching {
                http.newCall(Request.Builder().url("${account.baseUrl}/api/trace/$install").delete().build()).execute().close()
            }
        }
    }

    /** Called after every tool call, from any transport (local network, relay, scripts, the on-phone agent). */
    fun record(tool: String, params: JSONObject, result: CommandResult, ms: Long) {
        if (!enabled) return
        val e = event(tool, params, result, ms, System.currentTimeMillis())
        val full = synchronized(buffer) {
            buffer += e
            while (buffer.size > MAX_BUFFER) buffer.removeAt(0)
            buffer.size >= BATCH
        }
        if (full) scope.launch { flush() }
    }

    /** One event as it would be sent, for the owner to see before joining. */
    fun sample(): String = JSONObject()
        .put("install", "(a random id, not your account)")
        .put("app", BuildConfig.VERSION_NAME).put("sdk", Build.VERSION.SDK_INT).put("brand", Build.MANUFACTURER)
        .put("events", JSONArray().put(JSONObject()
            .put("t", System.currentTimeMillis() / 60_000).put("tool", "phone_tap").put("pkg", "com.android.settings")
            .put("ok", false).put("code", "not_found").put("ms", 1840).put("sel", "text").put("lines", 23)))
        .toString(2)

    private fun flush() {
        val install = prefs.getString("install", null) ?: return
        if (!enabled) return
        val batch = synchronized(buffer) { buffer.take(BATCH).also { buffer.subList(0, it.size).clear() } }
        if (batch.isEmpty()) return
        val body = JSONObject().put("install", install).put("app", BuildConfig.VERSION_NAME)
            .put("sdk", Build.VERSION.SDK_INT).put("brand", Build.MANUFACTURER.take(40)).put("events", JSONArray(batch))
        val sent = runCatching {
            http.newCall(
                Request.Builder().url("${account.baseUrl}/api/trace")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (!sent) {
            // Keep them for the next try, ahead of anything newer, within the buffer's bound.
            synchronized(buffer) {
                buffer.addAll(0, batch)
                while (buffer.size > MAX_BUFFER) buffer.removeAt(0)
            }
            Log.i(TAG, "trace upload failed; ${batch.size} events kept")
        }
    }

    companion object {
        private const val TAG = "Traces"
        private const val BATCH = 100
        private const val MAX_BUFFER = 500
        private const val FLUSH_EVERY_MS = 10 * 60_000L

        /** The only fields an event has. Arguments are read only to tell how an element was named, never their values. */
        internal fun event(tool: String, params: JSONObject, result: CommandResult, ms: Long, now: Long): JSONObject {
            val e = JSONObject().put("t", now / 60_000).put("tool", tool).put("ms", ms).put("ok", result is CommandResult.Ok)
            selector(params)?.let { e.put("sel", it) }
            when (result) {
                is CommandResult.Error -> e.put("code", result.code)
                is CommandResult.Ok -> {
                    val snap = result.payload.optString("snapshot")
                    if (snap.isNotEmpty()) e.put("lines", snap.count { it == '\n' })
                }
            }
            val pkg = (result as? CommandResult.Ok)?.payload?.optString("package")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: params.optString("package").takeIf { tool == "phone_launch" && it.isNotEmpty() }
            pkg?.takeIf { PACKAGE.matches(it) }?.let { e.put("pkg", it) }
            return e
        }

        private val PACKAGE = Regex("[A-Za-z0-9._]{1,150}")
        private val REF = Regex("e\\d+")

        private fun selector(p: JSONObject): String? {
            val target = p.optString("ref").ifEmpty { p.optString("target") }
            return when {
                target.isEmpty() -> if (p.has("x") && p.has("y")) "xy" else null
                REF.matches(target) -> "ref"
                target.startsWith("text=") -> "text"
                target.startsWith("id=") -> "id"
                target.startsWith("role=") -> "role"
                else -> null
            }
        }
    }
}
