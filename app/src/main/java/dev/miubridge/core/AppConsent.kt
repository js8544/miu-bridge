package dev.miubridge.core

import android.content.Context

/**
 * That the owner has read the risk notice, kept with its version and when, so a notice that changes
 * in substance is shown again.
 */
class AppConsent(context: Context) {
    private val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)

    val acceptedNotice: Boolean get() = prefs.getInt("notice_version", 0) >= NOTICE_VERSION

    fun acceptNotice() = prefs.edit().putInt("notice_version", NOTICE_VERSION).putLong("notice_at", System.currentTimeMillis()).apply()

    companion object {
        /** Raise when the risk notice changes in substance; owners are then shown it again. */
        const val NOTICE_VERSION = 1
    }
}
