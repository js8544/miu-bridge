package dev.miubridge.core

import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the time of the tool call now running went, in milliseconds per part. Tool calls run one at
 * a time, so one set of marks is enough: the server opens it before a call and reads it after.
 * Durations only, never content.
 */
object CallTiming {
    private val parts = ConcurrentHashMap<String, Long>()

    fun begin() = parts.clear()

    fun add(part: String, ms: Long) { parts.merge(part, ms, Long::plus) }

    /** Runs [block] and counts its duration under [part]. */
    inline fun <T> measure(part: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try { return block() } finally { add(part, SystemClock.elapsedRealtime() - start) }
    }

    fun into(json: JSONObject): JSONObject = json.apply { parts.forEach { (k, v) -> put(k, v) } }
}
