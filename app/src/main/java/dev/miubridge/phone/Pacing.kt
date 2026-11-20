package dev.miubridge.phone

import kotlin.random.Random

/**
 * How fast an agent may act in an app, so its use of the owner's accounts does not look like a
 * machine's. Nothing here names an app: the tempo comes from what kind of app it is and from what
 * its screen shows, and the wait from what the last action did.
 *
 * After each action that changes the screen ([noteAction]) the next one is not due until a little
 * later: a short pause when it stayed on the same screen, a longer one after arriving on a new
 * screen, longer still the more there is to read there. [waitBefore] is what remains of that pause;
 * time the agent already spent thinking counts, so a slow agent rarely waits at all and a script's
 * tight loop always does. An app also allows only so many new screens in a row ([limitFor]).
 *
 * Pure logic: the caller supplies the clock.
 */
class Pacing(private val random: Random = Random.Default) {

    enum class Tempo {
        /** No waiting: the phone's own apps, apps that never go online, or the owner chose speed. */
        FAST,
        /** The default for any app with an account behind it. */
        HUMAN,
        /** An app that has already shown a rate-limit or verification screen on this phone. */
        SLOW,
    }

    /** Milliseconds, except [readPerChar] (ms per character) and [extraChance] (0..1). */
    private class Limits(
        val sameScreen: LongRange, val newScreen: LongRange,
        val readPerChar: Double, val maxRead: Long,
        val extraChance: Double, val extra: LongRange,
        val maxWait: Long, val screenCap: Int,
    )

    private class Streak(var screens: Int, var lastAt: Long)

    private var dueAt = 0L
    private val streaks = HashMap<String, Streak>()

    /** How long to hold the next action so it is not sooner than the last one allows. */
    fun waitBefore(now: Long): Long = (dueAt - now).coerceAtLeast(0)

    /**
     * An action in [pkg] just finished. [newScreen]: it arrived somewhere new (not a scroll of the
     * same page), with [newChars] characters of new text to read.
     */
    fun noteAction(pkg: String?, tempo: Tempo, newScreen: Boolean, newChars: Int, now: Long) {
        val l = limits(tempo) ?: run { dueAt = 0; return }
        var wait = pick(if (newScreen) l.newScreen else l.sameScreen)
        if (newScreen) wait += (newChars * l.readPerChar).toLong().coerceAtMost(l.maxRead)
        if (random.nextDouble() < l.extraChance) wait += pick(l.extra)
        dueAt = now + wait.coerceAtMost(l.maxWait)
        if (newScreen && pkg != null) {
            val s = streaks.getOrPut(pkg) { Streak(0, now) }
            if (now - s.lastAt > STREAK_RESET_MS) s.screens = 0
            s.screens++
            s.lastAt = now
        }
    }

    /**
     * Null while the agent may go to another screen in [pkg]; otherwise how many milliseconds of
     * quiet remain before the count starts again. Scrolling and typing are never limited.
     */
    fun limitFor(pkg: String?, tempo: Tempo, now: Long): Long? {
        val l = limits(tempo) ?: return null
        val s = streaks[pkg ?: return null] ?: return null
        val quiet = now - s.lastAt
        if (quiet > STREAK_RESET_MS) return null
        return if (s.screens >= l.screenCap) STREAK_RESET_MS - quiet else null
    }

    private fun limits(tempo: Tempo): Limits? = when (tempo) {
        Tempo.FAST -> null
        Tempo.HUMAN -> HUMAN
        Tempo.SLOW -> SLOW
    }

    private fun pick(r: LongRange): Long = r.first + random.nextLong(r.last - r.first + 1)

    companion object {
        /** A run of screens ends after this long without a new one. */
        const val STREAK_RESET_MS = 3 * 60_000L

        private val HUMAN = Limits(
            sameScreen = 350L..1_000L, newScreen = 800L..1_900L,
            readPerChar = 5.0, maxRead = 2_500L,
            extraChance = 0.06, extra = 500L..1_600L,
            maxWait = 4_500L, screenCap = 60,
        )
        private val SLOW = Limits(
            sameScreen = 1_000L..2_400L, newScreen = 2_200L..4_600L,
            readPerChar = 12.0, maxRead = 6_000L,
            extraChance = 0.12, extra = 1_500L..4_000L,
            maxWait = 12_000L, screenCap = 25,
        )

        /**
         * Text an app shows when it thinks the account is acting too fast or wants a human check.
         * A match makes that app [Tempo.SLOW] from then on.
         */
        private val FRICTION = Regex(
            "操作(太|过于)?频繁|访问(太|过于)?频繁|请求(太|过于)?频繁|请稍后再试|安全验证|滑动验证|拖动滑块|滑块验证|人机验证|" +
                "账号(存在)?异常|环境异常|访问受限|行为异常|" +
                "too many (attempts|requests)|try again later|verify (that )?you('| a)re (a )?human|" +
                "unusual activity|suspicious activity|captcha|slide to verify|rate limit",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The phrase on screen that signals rate limiting or a human check, or null. Text inside
         * input fields is not the app speaking (the agent may have typed it), so those lines are skipped.
         */
        fun friction(screenText: String): String? =
            screenText.lineSequence().filterNot { INPUT_LINE.containsMatchIn(it) }.firstNotNullOfOrNull { FRICTION.find(it)?.value }

        private val INPUT_LINE = Regex("^\\s*\\[e\\d+] input\\b")

        /**
         * Whether [after] is a different screen from [before] rather than the same one changed a
         * little, and how much new text it has. Compares the lines of two snapshots.
         */
        fun change(before: String?, after: String): Pair<Boolean, Int> {
            val old = before?.lines()?.drop(1)?.map { it.trim().substringAfter("] ") }?.toSet().orEmpty()
            val now = after.lines().drop(1).map { it.trim().substringAfter("] ") }.filter { it.isNotEmpty() }
            if (now.isEmpty()) return false to 0
            val fresh = now.filter { it !in old }
            return (fresh.size * 2 > now.size) to fresh.sumOf { it.length }
        }
    }
}
