package dev.miubridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after a reboot or an app update if the owner left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in RESTART_ACTIONS && BridgeService.wanted(context)) BridgeService.start(context)
    }

    private companion object {
        val RESTART_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
