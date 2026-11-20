package dev.miubridge.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import dev.miubridge.App
import dev.miubridge.R
import org.json.JSONObject

/**
 * What the owner has agreed to. Two things are kept: that they read the risk notice (with its
 * version and when, so a changed notice is shown again), and which apps they have let agents use.
 *
 * An agent's first use of an app with an account behind it waits for the owner: many apps' terms
 * forbid automated use by third-party tools, and the consequence (a restricted or closed account)
 * falls on the owner, so the owner decides per app. The phone's own apps and apps without internet
 * access have no account at stake and are not asked about.
 */
class AppConsent(private val context: Context) {
    private val prefs = context.getSharedPreferences("consent", Context.MODE_PRIVATE)

    val acceptedNotice: Boolean get() = prefs.getInt("notice_version", 0) >= NOTICE_VERSION

    fun acceptNotice() = prefs.edit().putInt("notice_version", NOTICE_VERSION).putLong("notice_at", System.currentTimeMillis()).apply()

    /** The owner's choice to be asked once per app; off means every app is allowed. */
    var askPerApp: Boolean
        get() = prefs.getBoolean("ask_per_app", true)
        set(value) { prefs.edit().putBoolean("ask_per_app", value).apply() }

    val allowedApps: Set<String> get() = prefs.getStringSet("apps", emptySet()).orEmpty()

    fun allow(pkg: String) = prefs.edit().putStringSet("apps", allowedApps + pkg).apply()

    fun forgetApps() = prefs.edit().remove("apps").apply()

    fun isAllowed(pkg: String): Boolean {
        if (!askPerApp || pkg == context.packageName || pkg in allowedApps) return true
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return true // not installed: the launch says so
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val online = pm.checkPermission(android.Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED
        return system || !online
    }

    /** For a call that would open an app the owner has not allowed: asks the owner and returns the refusal. Null otherwise. */
    fun check(tool: String, params: JSONObject): CommandResult.Error? {
        val pkg = when (tool) {
            "phone_launch" -> params.optString("package")
            "phone_open_url" -> runCatching {
                context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(params.optString("url"))), PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.takeIf { it != "android" }
            }.getOrNull().orEmpty()
            else -> return null
        }
        if (pkg.isBlank() || isAllowed(pkg)) return null
        val label = label(pkg)
        ask(pkg, label)
        return CommandResult.Error(
            "The owner has not yet allowed agents to use $label ($pkg). The phone is asking them now in a notification: " +
                "tell the owner to allow it there, then call this again. Do not try another way into the app.",
            "owner_consent_needed",
        )
    }

    private fun label(pkg: String): String =
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    private fun ask(pkg: String, label: String) {
        val id = NOTIFICATION_BASE + (pkg.hashCode() and 0xffff)
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            context, id * 2 + if (allow) 1 else 0,
            Intent(context, Receiver::class.java).putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_ALLOW, allow).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.consent_app_body, label)
        val notification = Notification.Builder(context, App.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.consent_app_title, label))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.consent_app_allow), action(true)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.consent_app_deny), action(false)).build())
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            if (intent.getBooleanExtra(EXTRA_ALLOW, false)) AppConsent(context).allow(pkg)
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_ID, 0))
        }
    }

    companion object {
        /** Raise when the risk notice changes in substance; owners are then shown it again. */
        const val NOTICE_VERSION = 1
        private const val NOTIFICATION_BASE = 0x40000
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_ALLOW = "allow"
        private const val EXTRA_ID = "id"
    }
}
