package dev.miubridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GesturePathTest {
    @Test fun endsExactlyAtTheTarget() {
        val p = GesturePath.swipe(500, 1600, 500, 600, 300, Random(1))
        assertEquals(500f, p.last().x)
        assertEquals(600f, p.last().y)
    }

    @Test fun bowsOffTheStraightLineButStaysNear() {
        val p = GesturePath.swipe(500, 1600, 500, 600, 300, Random(2))
        val off = p.maxOf { abs(it.x - 500f) }
        assertTrue(off > 5f && off < 120f)
    }

    @Test fun movesFasterInTheMiddleThanAtTheEnds() {
        val p = GesturePath.swipe(500, 1600, 500, 600, 320, Random(3))
        val ys = listOf(1600f) + p.map { it.y }
        val step = { i: Int -> abs(ys[i + 1] - ys[i]) }
        assertTrue(step(ys.size / 2) > step(0) * 2)
    }

    @Test fun timingIsUnevenAndTakesAboutTheDuration() {
        val p = GesturePath.swipe(100, 100, 900, 100, 400, Random(4))
        assertTrue(p.map { it.delayMs }.toSet().size > 3)
        assertTrue(p.sumOf { it.delayMs } in 300..520)
    }

    @Test fun twoSwipesAreNotIdentical() {
        val a = GesturePath.swipe(500, 1600, 500, 600, 300, Random(5)).map { it.x }
        val b = GesturePath.swipe(500, 1600, 500, 600, 300, Random(6)).map { it.x }
        assertTrue(a != b)
    }
}
