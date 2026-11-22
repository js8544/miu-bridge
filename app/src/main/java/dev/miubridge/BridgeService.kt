package dev.miubridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.app.AlarmManager
import android.os.SystemClock
import dev.miubridge.core.Awake
import dev.miubridge.server.McpServer
import dev.miubridge.server.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The background service: holds the MCP server and the phone's shell, with a notification and a
 * stop button. The single owner of their lifetime.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: McpServer? = null
    private var relay: RelayClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK) {
            // Awake long enough for the relay link to notice it has gone quiet and reconnect.
            Awake.touch(20_000)
            scheduleTick()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            setWanted(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        setWanted(this, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notif_starting)))
        }
        if (server == null) {
            val app = application as App
            // No standing wake lock: the phone sleeps between requests. Awake for start-up only.
            Awake.init(this)
            Awake.touch(60_000)
            scheduleTick()
            server = McpServer(app.tools, app.token, sealedOnly = { app.token.sealedOnly }).also {
                it.start()
                _running.value = true
                relay = RelayClient(this, it).apply { start() }
            }
            app.phone.applyStandby(serviceRunning = true)
            scope.launch { app.phone.ensureShell() }
            scope.launch {
                app.phone.state.collect { p ->
                    val text = if (p.shellReady) getString(R.string.notif_ready) else p.message.ifBlank { getString(R.string.notif_not_ready) }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        relay?.stop()
        relay = null
        server?.stop()
        server = null
        _running.value = false
        (application as App).phone.applyStandby(serviceRunning = false)
        (application as App).phone.close()
        getSystemService(AlarmManager::class.java).cancel(tick())
        Awake.release()
        super.onDestroy()
    }

    private fun tick() = PendingIntent.getService(
        this, 2, Intent(this, BridgeService::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A sparse wake-up while the phone sleeps, so a relay link that died in its sleep is found and remade. */
    private fun scheduleTick() {
        getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, tick())
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Miu Bridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop_short), stop).build())
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.miubridge.STOP"
        private const val ACTION_TICK = "dev.miubridge.TICK"
        private const val TICK_MS = 10 * 60_000L
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
        }

        fun wanted(context: Context) = context.getSharedPreferences("service", Context.MODE_PRIVATE).getBoolean("on", false)
        private fun setWanted(context: Context, on: Boolean) =
            context.getSharedPreferences("service", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }
}
