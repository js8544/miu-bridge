package dev.miubridge.server

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** The bearer token agents must send. Kept in app-private storage; never logged. */
class AccessToken(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    val value: String
        get() = prefs.getString(KEY, null) ?: regenerate()

    fun regenerate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = "pb_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    /** Constant-time check of an `Authorization` header value. */
    fun matches(header: String?): Boolean {
        val given = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), value.toByteArray())
    }

    private companion object {
        const val KEY = "token"
    }
}
