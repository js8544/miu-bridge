package dev.miubridge.phone

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over Ed25519 as adbd's wireless-debugging pairing runs it (BoringSSL's spake25519):
 * the pairing code (plus TLS-exported key material) is the password, the client is "alice" and
 * masks with M, the server is "bob" and masks with N. Pairing happens once, so plain BigInteger
 * arithmetic on affine points is fast enough.
 */
class Spake2(private val role: Role, private val myName: ByteArray, private val theirName: ByteArray, password: ByteArray) {
    enum class Role { ALICE, BOB }

    private val privateKey: BigInteger
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar: BigInteger

    /** Our 32-byte message, sent to the peer. */
    val message: ByteArray

    init {
        val r = ByteArray(64).also { SecureRandom().nextBytes(it) }
        // Multiple of the cofactor, so small-order parts of the peer's point drop out.
        privateKey = leInt(r).mod(Ed25519.L).shiftLeft(3)
        passwordScalar = clearLowBits(leInt(passwordHash).mod(Ed25519.L))
        val mask = Ed25519.mul(passwordScalar, if (role == Role.ALICE) Ed25519.M else Ed25519.N)
        message = Ed25519.encode(Ed25519.add(Ed25519.mul(privateKey, Ed25519.B), mask))
    }

    /** Derives the 64-byte shared key from the peer's message. Throws if it is not a curve point. */
    fun process(theirMessage: ByteArray): ByteArray {
        require(theirMessage.size == 32) { "SPAKE2 message must be 32 bytes" }
        val qStar = Ed25519.decode(theirMessage) ?: throw IllegalArgumentException("peer's SPAKE2 point is not on the curve")
        val peersMask = Ed25519.mul(passwordScalar, if (role == Role.ALICE) Ed25519.N else Ed25519.M)
        val shared = Ed25519.encode(Ed25519.mul(privateKey, Ed25519.add(qStar, Ed25519.negate(peersMask))))
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            var len = b.size.toLong()
            repeat(8) { sha.update((len and 0xff).toByte()); len = len shr 8 }
            sha.update(b)
        }
        if (role == Role.ALICE) {
            put(myName); put(theirName); put(message); put(theirMessage)
        } else {
            put(theirName); put(myName); put(theirMessage); put(message)
        }
        put(shared)
        put(passwordHash)
        return sha.digest()
    }

    companion object {
        /** adb's names include the C string terminator. */
        val ADB_CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val ADB_SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * BoringSSL adds multiples of the group order to the reduced password scalar until it is a
         * multiple of eight; both sides must use exactly the same scalar.
         */
        internal fun clearLowBits(w: BigInteger): BigInteger {
            var s = w
            var order = Ed25519.L
            for (bit in 0..2) {
                if (s.testBit(bit)) s = s.add(order)
                order = order.shiftLeft(1)
            }
            return s
        }

        internal fun leInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    }
}

/** Minimal Edwards25519 arithmetic in affine coordinates. Points are (x, y). */
internal object Ed25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    data class Point(val x: BigInteger, val y: BigInteger)

    val ZERO = Point(BigInteger.ZERO, BigInteger.ONE)
    val B: Point = decode(fromHex("5866666666666666666666666666666666666666666666666666666666666666"))!!

    /** SPAKE2 mask points from BoringSSL (hash-derived; see spake25519.cc). */
    val M = Point(
        BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
        BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"),
    )
    val N = Point(
        BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
        BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"),
    )

    fun add(a: Point, b: Point): Point {
        val t = D.multiply(a.x).multiply(b.x).multiply(a.y).multiply(b.y).mod(P)
        val x = a.x.multiply(b.y).add(a.y.multiply(b.x)).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P)
        val y = a.y.multiply(b.y).add(a.x.multiply(b.x)).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P)
        return Point(x, y)
    }

    fun negate(a: Point) = Point(P.subtract(a.x).mod(P), a.y)

    fun mul(k: BigInteger, a: Point): Point {
        var result = ZERO
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, a)
        }
        return result
    }

    fun encode(a: Point): ByteArray {
        val out = ByteArray(32)
        val y = a.y.toByteArray().reversedArray() // little-endian, maybe with a sign byte at the end
        y.copyInto(out, 0, 0, minOf(32, y.size))
        if (a.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun decode(b: ByteArray): Point? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() shr 7) and 1
        val yBytes = b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = BigInteger(1, yBytes.reversedArray())
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) {
            x = x.multiply(SQRT_M1).mod(P)
            if (x.multiply(x).mod(P) != x2) return null
        }
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return Point(x, y)
    }

    fun fromHex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
