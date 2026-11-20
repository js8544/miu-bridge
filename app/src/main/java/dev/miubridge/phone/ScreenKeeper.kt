package dev.miubridge.phone

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the phone's screen on while an agent is working, and tells when the phone is locked.
 * Below Android 13 a background display cannot work behind the lock screen (the keyguard covers it
 * and takes input focus), so the screen must stay on and unlocked during a task.
 */
internal class ScreenKeeper(context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    @Suppress("DEPRECATION") // the only way for an app to keep the screen on without a window
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "miubridge:task")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val standbyLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "miubridge:standby")
        .apply { setReferenceCounted(false) }

    /**
     * Standby: keeps the screen dimly on for as long as it is set, so the phone never locks itself
     * and an agent can start a task at any time. Costs battery; meant for a phone on a charger.
     */
    fun setStandby(on: Boolean) {
        if (on) { if (!standbyLock.isHeld) standbyLock.acquire() } else if (standbyLock.isHeld) standbyLock.release()
    }

    /** Holds the screen on until [HOLD_MS] after the latest call. */
    fun touch() = lock.acquire(HOLD_MS)

    fun release() {
        if (lock.isHeld) lock.release()
    }

    /** True when the lock screen blocks the background display on this Android version. */
    fun blocksBackground(): Boolean = Build.VERSION.SDK_INT < 33 && keyguard.isKeyguardLocked

    private companion object {
        const val HOLD_MS = 2 * 60_000L
    }
}
