package dev.miubridge.core

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the CPU awake only while there is work: for the length of a request and a short tail after
 * it. Between requests the phone is free to sleep; the relay's keepalive and the next request wake
 * it, the way a push message would.
 */
object Awake {
    private const val TAIL_MS = 15_000L
    @Volatile private var lock: PowerManager.WakeLock? = null
    private val working = AtomicInteger()
    @Volatile private var heldUntil = 0L

    fun init(context: Context) {
        if (lock != null) return
        lock = context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miubridge:work").apply { setReferenceCounted(false) }
    }

    /** Stay awake for [ms] from now: start-up, a reconnect, a periodic check. */
    fun touch(ms: Long = TAIL_MS) {
        val until = SystemClock.elapsedRealtime() + ms
        // A shorter request must not cut a longer one short (a reconnect during start-up).
        if (working.get() == 0 && until > heldUntil) { heldUntil = until; lock?.acquire(ms) }
    }

    /** Stay awake for the whole of [block], then for the tail. */
    suspend fun <T> during(block: suspend () -> T): T {
        working.incrementAndGet()
        lock?.acquire(10 * 60_000L + TAIL_MS) // a bound, in case something never returns
        try {
            return block()
        } finally {
            if (working.decrementAndGet() == 0) { heldUntil = SystemClock.elapsedRealtime() + TAIL_MS; lock?.acquire(TAIL_MS) }
        }
    }

    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}
