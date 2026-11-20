package dev.miubridge.phone

import kotlin.math.hypot
import kotlin.random.Random

/**
 * The shape of a swipe as a finger makes it: a slight bow instead of a ruler-straight line, slow at
 * both ends and quick in the middle, with uneven gaps between samples. Pure, so it can be tested.
 */
object GesturePath {
    class Sample(val x: Float, val y: Float, val delayMs: Long)

    /** Points from just after ([x1],[y1]) to exactly ([x2],[y2]); each is sent [Sample.delayMs] after the one before. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, random: Random = Random.Default): List<Sample> {
        val dx = (x2 - x1).toFloat()
        val dy = (y2 - y1).toFloat()
        val length = hypot(dx, dy)
        val steps = (durationMs / 16).toInt().coerceIn(4, 120)
        // The control point sits off the midpoint, to one side, by a few percent of the length.
        val bow = if (length < 1f) 0f else length * (0.03f + random.nextFloat() * 0.07f) * (if (random.nextBoolean()) 1f else -1f)
        val cx = x1 + dx / 2 + (if (length < 1f) 0f else -dy / length * bow)
        val cy = y1 + dy / 2 + (if (length < 1f) 0f else dx / length * bow)
        val gap = durationMs.toFloat() / steps
        return (1..steps).map { i ->
            val t = i.toFloat() / steps
            val e = t * t * (3 - 2 * t) // ease in and out
            val a = (1 - e) * (1 - e)
            val b = 2 * (1 - e) * e
            val c = e * e
            val last = i == steps
            Sample(
                if (last) x2.toFloat() else a * x1 + b * cx + c * x2,
                if (last) y2.toFloat() else a * y1 + b * cy + c * y2,
                (gap * (0.65f + random.nextFloat() * 0.7f)).toLong().coerceAtLeast(1),
            )
        }
    }
}
