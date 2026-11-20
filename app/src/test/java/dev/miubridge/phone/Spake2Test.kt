package dev.miubridge.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Spake2Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun maskPointsMatchBoringSslEncodings() {
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", hex(Ed25519.encode(Ed25519.M)))
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", hex(Ed25519.encode(Ed25519.N)))
        assertEquals(Ed25519.M, Ed25519.decode(Ed25519.encode(Ed25519.M)))
    }

    @Test fun baseHasPrimeOrder() {
        assertEquals(Ed25519.ZERO, Ed25519.mul(Ed25519.L, Ed25519.B))
    }

    @Test fun passwordScalarIsMultipleOfEightAndCongruent() {
        val w = Spake2.leInt(ByteArray(64) { (it * 37 + 5).toByte() }).mod(Ed25519.L)
        val s = Spake2.clearLowBits(w)
        assertEquals(0, s.mod(8.toBigInteger()).toInt())
        assertEquals(w, s.mod(Ed25519.L))
    }

    @Test fun bothSidesDeriveTheSameKey() {
        val pw = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val a = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME, pw)
        val b = Spake2(Spake2.Role.BOB, Spake2.ADB_SERVER_NAME, Spake2.ADB_CLIENT_NAME, pw)
        assertArrayEquals(a.process(b.message), b.process(a.message))
    }

    @Test fun wrongPasswordGivesDifferentKeys() {
        val a = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME, "123456".toByteArray())
        val b = Spake2(Spake2.Role.BOB, Spake2.ADB_SERVER_NAME, Spake2.ADB_CLIENT_NAME, "654321".toByteArray())
        assertFalse(a.process(b.message).contentEquals(b.process(a.message)))
    }
}
