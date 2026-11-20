package dev.miubridge.phone

import dev.miubridge.phone.Pacing.Tempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PacingTest {
    @Test fun fastNeverWaitsOrLimits() {
        val p = Pacing(Random(1))
        repeat(200) { p.noteAction("a", Tempo.FAST, newScreen = true, newChars = 500, now = it * 10L) }
        assertEquals(0, p.waitBefore(2_000))
        assertNull(p.limitFor("a", Tempo.FAST, 2_000))
    }

    @Test fun newScreenWaitsLongerThanSameScreen() {
        val same = (1..200).map { Pacing(Random(it)).apply { noteAction("a", Tempo.HUMAN, false, 0, 0) }.waitBefore(0) }
        val fresh = (1..200).map { Pacing(Random(it)).apply { noteAction("a", Tempo.HUMAN, true, 300, 0) }.waitBefore(0) }
        assertTrue(same.average() < fresh.average())
        assertTrue(same.all { it in 350..4_500 })
        assertTrue(fresh.all { it in 800..4_500 })
    }

    @Test fun slowWaitsLongerThanHuman() {
        val human = (1..200).map { Pacing(Random(it)).apply { noteAction("a", Tempo.HUMAN, true, 200, 0) }.waitBefore(0) }.average()
        val slow = (1..200).map { Pacing(Random(it)).apply { noteAction("a", Tempo.SLOW, true, 200, 0) }.waitBefore(0) }.average()
        assertTrue(slow > human * 1.5)
    }

    @Test fun timeAlreadySpentCounts() {
        val p = Pacing(Random(3))
        p.noteAction("a", Tempo.HUMAN, true, 0, now = 1_000)
        val wait = p.waitBefore(1_000)
        assertTrue(wait > 0)
        assertEquals((wait - 500).coerceAtLeast(0), p.waitBefore(1_500))
        assertEquals(0, p.waitBefore(1_000 + wait + 1))
    }

    @Test fun waitsVary() {
        val waits = (1..50).map { Pacing(Random(it)).apply { noteAction("a", Tempo.HUMAN, false, 0, 0) }.waitBefore(0) }.toSet()
        assertTrue(waits.size > 20)
    }

    @Test fun screenLimitStopsARunAndLiftsAfterQuiet() {
        val p = Pacing(Random(5))
        var now = 0L
        repeat(25) { p.noteAction("a", Tempo.SLOW, true, 10, now); now += 5_000 }
        assertNotNull(p.limitFor("a", Tempo.SLOW, now))
        assertNull(p.limitFor("b", Tempo.SLOW, now))
        assertNull(p.limitFor("a", Tempo.SLOW, now + Pacing.STREAK_RESET_MS + 1))
    }

    @Test fun scrollingTheSamePageDoesNotCountAsScreens() {
        val p = Pacing(Random(5))
        repeat(200) { p.noteAction("a", Tempo.SLOW, newScreen = false, newChars = 0, now = it * 3_000L) }
        assertNull(p.limitFor("a", Tempo.SLOW, 600_000))
    }

    @Test fun recognizesFrictionInEitherLanguage() {
        assertEquals("操作太频繁", Pacing.friction("[e1] text \"操作太频繁，请稍后\""))
        assertNotNull(Pacing.friction("Please verify you are human"))
        assertNotNull(Pacing.friction("拖动滑块完成验证"))
        assertNull(Pacing.friction("[e1] button \"发送\"\n[e2] text \"今天天气不错\""))
        // what the agent typed into a field is not the app complaining
        assertNull(Pacing.friction("display\n[e1] button \"Back\"\n  [e2] input \"captcha\" @529,94 (focused)"))
        assertNotNull(Pacing.friction("display\n[e2] input \"hi\"\n[e3] text \"Too many requests\""))
    }

    @Test fun tellsANewScreenFromASmallChange() {
        val before = "display\n[e1] button \"A\"\n[e2] button \"B\"\n[e3] text \"C\"\n[e4] text \"D\""
        val (same, _) = Pacing.change(before, "display\n[e1] button \"A\"\n[e2] button \"B\"\n[e3] text \"C\"\n[e4] text \"D2\"")
        assertFalse(same)
        val (fresh, chars) = Pacing.change(before, "display\n[e1] text \"Wi-Fi\"\n[e2] text \"Bluetooth\"\n[e3] button \"A\"")
        assertTrue(fresh)
        assertTrue(chars > 10)
        // refs renumber between snapshots; the same line under another ref is not new
        assertFalse(Pacing.change(before, "display\n[e7] button \"A\"\n[e8] button \"B\"\n[e9] text \"C\"").first)
    }
}
