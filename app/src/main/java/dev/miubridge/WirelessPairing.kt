package dev.miubridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import dev.miubridge.phone.AdbKey
import dev.miubridge.phone.AdbPairing
import dev.miubridge.phone.PairingCodeException
import dev.miubridge.phone.WirelessAdb
import kotlin.concurrent.thread

/**
 * Pairs the app with this phone's wireless debugging, without a computer. The pairing code is shown
 * in a Settings dialog that closes if the owner switches apps, so the code is typed into this
 * notification instead, right on top of Settings.
 */
object WirelessPairing {
    private const val NOTIFICATION_ID = 20
    private const val KEY_CODE = "code"
    private const val TAG = "WirelessPairing"

    /** Shows the code-entry notification and opens the developer settings where wireless debugging lives. */
    fun start(context: Context) {
        notify(context, context.getString(R.string.pair_title), context.getString(R.string.pair_prompt), withInput = true)
        openWirelessDebuggingSettings(context)
        watchForCode(context.applicationContext)
    }

    private val watch = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The phone advertises its pairing service only while the six digits are on screen. That is the
     * moment the owner needs the input, so bring the notification back to the top of the screen then,
     * instead of expecting them to remember it is in the shade.
     */
    private fun watchForCode(context: Context) {
        val mine = watch.incrementAndGet()
        thread(name = "pairing-code-watch") {
            val port = runCatching { WirelessAdb.findLocalPort(context, WirelessAdb.PAIRING_SERVICE, 5 * 60_000) }.getOrNull()
            if (port == null || watch.get() != mine || WirelessAdb.isPaired(context)) return@thread
            val manager = context.getSystemService(NotificationManager::class.java)
            // A fresh notification, not an update: only a new one drops down over the settings page.
            manager.cancel(NOTIFICATION_ID)
            notify(context, context.getString(R.string.pair_code_seen_title), context.getString(R.string.pair_code_seen_body), withInput = true)
        }
    }

    fun openWirelessDebuggingSettings(context: Context) {
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(dev)
        } catch (e: ActivityNotFoundException) {
            // Developer options are not on yet: the owner taps the build number seven times there.
            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun pair(context: Context, code: String, paired: () -> Unit = {}) {
        watch.incrementAndGet()
        notify(context, context.getString(R.string.pair_working_title), context.getString(R.string.pair_working_body))
        val port = WirelessAdb.findLocalPort(context, WirelessAdb.PAIRING_SERVICE, 15_000)
        if (port == null) {
            notify(context, context.getString(R.string.pair_notfound_title), context.getString(R.string.pair_notfound_body), withInput = true)
            return
        }
        try {
            AdbPairing.pair("127.0.0.1", port, code, AdbKey.of(context), AdbKey.NAME)
        } catch (e: PairingCodeException) {
            notify(context, context.getString(R.string.pair_wrong_title), context.getString(R.string.pair_wrong_body), withInput = true)
            return
        } catch (e: Exception) {
            Log.w(TAG, "pairing failed", e)
            notify(context, context.getString(R.string.pair_failed_title), context.getString(R.string.pair_failed_body), withInput = true)
            return
        }
        WirelessAdb.markPaired(context)
        notify(context, context.getString(R.string.pair_ok_title), context.getString(R.string.pair_ok_body))
        val app = context.applicationContext as App
        BridgeService.start(context)
        paired()
        val ok = kotlinx.coroutines.runBlocking { app.phone.ensureShell() }
        notify(context, context.getString(if (ok) R.string.pair_done_title else R.string.pair_done_no_screen_title), if (ok) context.getString(R.string.pair_done_body) else app.phone.state.value.message)
    }

    private fun notify(context: Context, title: String, text: String, withInput: Boolean = false) {
        val builder = Notification.Builder(context, App.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
        if (withInput) {
            val reply = PendingIntent.getBroadcast(
                context, 0, Intent(context, CodeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pair_code_hint)).build()
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.pair_action), reply).addRemoteInput(input).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    class CodeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() }
            if (code.isNullOrEmpty()) return
            val pending = goAsync()
            val released = java.util.concurrent.atomic.AtomicBoolean()
            fun release() { if (released.compareAndSet(false, true)) pending.finish() }
            val app = context.applicationContext
            thread(name = "adb-pairing") {
                try {
                    // Starting the background screen takes longer than a broadcast may run: the system
                    // reports the app as not responding. Let the broadcast go once pairing is settled;
                    // the foreground service keeps the process alive for the rest.
                    pair(app, code, ::release)
                } finally {
                    release()
                }
            }
        }
    }
}
