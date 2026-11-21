package dev.miubridge.server

import android.content.Context
import dev.miubridge.R
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * This phone's place on the relay: a device id and key, which the phone gets by naming itself, with
 * no account. Doing it again on the same phone keeps the id, so the MCP URL already given to agents
 * keeps working. Signing in with an email remains for a phone that already did, and for a paid plan.
 */
class Account(private val context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
    // Generous timeouts: on a slow path to the relay (a VPN, a distant network) the handshake alone takes
    // seconds and sending the mail takes more; with the defaults the relay finished the work but the
    // phone had already given up, so sign-in looked dead while codes kept arriving.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** The relay this phone signs in to: the hosted one unless the owner pointed the app at their own. */
    val baseUrl: String get() = prefs.getString("relay_url", null) ?: DEFAULT_RELAY_URL
    val usesDefaultRelay: Boolean get() = baseUrl == DEFAULT_RELAY_URL

    /**
     * Points the app at another relay ([url] blank for the hosted one). A device belongs to one
     * relay, so this takes the phone off the current one and forgets its device id. Returns false when [url]
     * is not an http(s) address.
     */
    suspend fun useRelay(url: String): Boolean {
        val next = relayAddress(url, DEFAULT_RELAY_URL) ?: return false
        if (next == baseUrl) return true
        unlink()
        prefs.edit().remove("device_id").apply {
            if (next == DEFAULT_RELAY_URL) remove("relay_url") else putString("relay_url", next)
        }.apply()
        return true
    }
    val email: String? get() = prefs.getString("email", null)
    val deviceId: String? get() = prefs.getString("device_id", null)
    val deviceKey: String? get() = prefs.getString("device_key", null)
    /** On the relay: agents on other networks can reach this phone. */
    val linked: Boolean get() = deviceId != null && deviceKey != null
    val mcpUrl: String? get() = deviceId?.takeIf { linked }?.let { "$baseUrl/d/$it/mcp" }

    /**
     * Puts this phone on the relay, without an account. The relay counts a phone's monthly use under
     * an id only this app on this phone has (Android gives each app its own), sent as a hash, so
     * clearing the app's data does not make it a new phone. Returns an error message, or null on success.
     */
    suspend fun link(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("hardware", hardware())
            val device = try { post("/api/phones", body) } catch (e: java.io.IOException) { post("/api/phones", body) }
            prefs.edit()
                .putString("device_id", device.getString("deviceId"))
                .putString("device_key", device.getString("deviceKey"))
                .apply()
            null
        }.getOrElse { message(it) }
    }

    private fun hardware(): String {
        val id = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: prefs.getString("install", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("install", it).apply() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("miubridge:$id".toByteArray())
        return android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /** What came of asking for a code: a [message] to show if it did not plainly succeed, and whether a code may be on its way. */
    class Sent(val message: String?, val codeMayArrive: Boolean)

    /**
     * Emails a sign-in code. When the relay gives no answer, or says one was sent a moment ago, a
     * code is (or may be) in the owner's mail, so the caller should still let them enter it.
     */
    suspend fun sendCode(email: String): Sent = withContext(Dispatchers.IO) {
        try {
            post("/api/auth/send", JSONObject().put("email", email))
            Sent(null, true)
        } catch (e: ApiError) {
            Sent(message(e), e.code == "code_recent")
        } catch (e: Exception) {
            Sent(context.getString(R.string.err_send_unsure), true)
        }
    }

    /**
     * Signs in with the emailed code (the first sign-in creates the account) and registers this
     * phone. Returns an error message, or null on success.
     */
    suspend fun signIn(email: String, code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val auth = post("/api/auth/verify", JSONObject().put("email", email).put("code", code))
            val session = auth.getString("token")
            val body = JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            deviceId?.let { body.put("deviceId", it) }
            // The sign-in itself is done; do not lose it to one dropped request.
            val device = try { post("/api/devices", body, session) } catch (e: java.io.IOException) { post("/api/devices", body, session) }
            prefs.edit()
                .putString("session", session)
                .putString("email", auth.getString("email"))
                .putString("device_id", device.getString("deviceId"))
                .putString("device_key", device.getString("deviceKey"))
                .apply()
            null
        }.getOrElse { message(it) }
    }

    /** This month's metered remote tool calls: [limit] null means unlimited. */
    data class Usage(val used: Int, val limit: Int?)

    /** This phone's usage this month, or null when it is not on the relay or the relay cannot be reached. */
    suspend fun usage(): Usage? = withContext(Dispatchers.IO) {
        val id = deviceId ?: return@withContext null
        val key = deviceKey ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$baseUrl/d/$id/usage").header("X-Device-Key", key).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val u = JSONObject(res.body?.string().orEmpty())
                Usage(u.optInt("used"), if (u.isNull("limit")) null else u.optInt("limit"))
            }
        }.getOrNull()
    }

    /** Takes this phone off the relay (and signs out, if it was signed in). Linking again restores the same MCP URL. */
    suspend fun unlink() = withContext(Dispatchers.IO) {
        val session = prefs.getString("session", null)
        prefs.edit().remove("session").remove("device_key").remove("email").apply()
        if (session != null) runCatching { post("/api/logout", JSONObject(), session) }
    }

    /** The relay answered with an error [code] the app can put in the owner's language. */
    private class ApiError(val code: String, val status: Int) : Exception(code)

    private fun message(e: Throwable): String {
        if (e !is ApiError) return context.getString(R.string.err_network)
        return when (e.code) {
            "bad_email" -> context.getString(R.string.err_bad_email)
            "rate_limited" -> context.getString(R.string.err_rate_limited)
            "code_recent" -> context.getString(R.string.err_code_recent)
            "too_many_codes" -> context.getString(R.string.err_too_many_codes)
            "code_expired" -> context.getString(R.string.err_code_expired)
            "code_wrong" -> context.getString(R.string.err_code_wrong)
            "send_failed" -> context.getString(R.string.err_send_failed)
            "login_required", "session_expired" -> context.getString(R.string.err_session)
            "device_limit" -> context.getString(R.string.err_device_limit)
            else -> context.getString(R.string.err_server, e.status)
        }
    }

    private fun post(path: String, body: JSONObject, session: String? = null): JSONObject {
        val req = Request.Builder().url(baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { session?.let { header("Authorization", "Bearer $it") } }
            .build()
        http.newCall(req).execute().use { res ->
            val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) throw ApiError(json.optString("code"), res.code)
            return json
        }
    }

    companion object {
        /** The relay address [typed] stands for: [fallback] when blank, null when it is not a bare http(s) origin. */
        fun relayAddress(typed: String, fallback: String): String? {
            val wanted = typed.trim().trimEnd('/')
            if (wanted.isEmpty()) return fallback
            val ok = wanted.toHttpUrlOrNull()?.let { it.encodedPath == "/" && it.query == null && it.fragment == null } == true
            return wanted.takeIf { ok }
        }

        /** The hosted relay; set `relayUrl` in gradle.properties to build with another default. */
        const val DEFAULT_RELAY_URL = dev.miubridge.BuildConfig.RELAY_URL
    }
}
