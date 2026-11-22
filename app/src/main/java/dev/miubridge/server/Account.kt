package dev.miubridge.server

import android.content.Context
import dev.miubridge.R
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * keeps working.
 */
class Account(private val context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
    // Generous timeouts: on a slow path to the relay (a VPN, a distant network) the handshake alone takes seconds.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * The relay this phone uses: the owner's own if they entered one, otherwise the hosted relay that
     * answered soonest when remote access was first turned on (see [link]).
     */
    val baseUrl: String get() = prefs.getString("relay_url", null)
        ?: prefs.getString("relay_near", null)?.takeIf { it in HOSTED }
        ?: DEFAULT_RELAY_URL
    val usesDefaultRelay: Boolean get() = prefs.getString("relay_url", null) == null

    /**
     * Points the app at another relay ([url] blank for the hosted one). A device belongs to one
     * relay, so this takes the phone off the current one and forgets its device id. Returns false when [url]
     * is not an http(s) address.
     */
    suspend fun useRelay(url: String): Boolean {
        val next = relayAddress(url, "") ?: return false
        if (next.isEmpty() && usesDefaultRelay) return true
        if (next == baseUrl) return true
        unlink()
        // Back to the hosted relays, the nearest is found again the next time remote access is turned on.
        prefs.edit().remove("device_id").remove("relay_near").apply {
            if (next.isEmpty()) remove("relay_url") else putString("relay_url", next)
        }.apply()
        return true
    }
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
            // A phone that has not been on a hosted relay yet goes to the one that answers soonest from
            // where it is. It is not moved afterwards: its address would change under its agents.
            if (usesDefaultRelay && deviceId == null && HOSTED.size > 1) prefs.edit().putString("relay_near", nearest()).apply()
            val body = JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("hardware", hardware())
            val device = try { post("/api/phones", body) } catch (e: java.io.IOException) { post("/api/phones", body) }
            prefs.edit()
                .putString("device_id", device.getString("deviceId"))
                .putString("device_key", device.getString("deviceKey"))
                .apply()
            null
        }.getOrElse { message(it) }
    }

    /** The hosted relay with the shortest round trip from here; the first one if none answers. */
    private suspend fun nearest(): String = coroutineScope {
        val quick = http.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
        HOSTED.map { url ->
            async {
                // The second request rides the connection the first one opened, so it times one round trip.
                url to runCatching {
                    repeat(2) { quick.newCall(Request.Builder().url("$url/").build()).execute().close() }
                    val start = System.nanoTime()
                    quick.newCall(Request.Builder().url("$url/").build()).execute().close()
                    System.nanoTime() - start
                }.getOrDefault(Long.MAX_VALUE)
            }
        }.awaitAll().minBy { it.second }.takeIf { it.second != Long.MAX_VALUE }?.first ?: DEFAULT_RELAY_URL
    }

    private fun hardware(): String {
        val id = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: prefs.getString("install", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("install", it).apply() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("miubridge:$id".toByteArray())
        return android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
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

    /** Takes this phone off the relay. Linking again restores the same MCP URL. */
    fun unlink() {
        // "session" and "email" are what a phone that once signed in to an account still holds.
        prefs.edit().remove("device_key").remove("session").remove("email").apply()
    }

    /** The relay answered with an error [code] the app can put in the owner's language. */
    private class ApiError(val code: String, val status: Int) : Exception(code)

    private fun message(e: Throwable): String {
        if (e !is ApiError) return context.getString(R.string.err_network)
        return if (e.code == "rate_limited") context.getString(R.string.err_rate_limited) else context.getString(R.string.err_server, e.status)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
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

        /**
         * The hosted relays; set `relayUrl` in gradle.properties (comma-separated) to build with others.
         * The first is the one to fall back on.
         */
        val HOSTED: List<String> = dev.miubridge.BuildConfig.RELAY_URL.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        val DEFAULT_RELAY_URL: String = HOSTED.first()
    }
}
