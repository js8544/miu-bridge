package dev.miubridge.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

class AdbRsaTest {
    private val e = BigInteger.valueOf(65537)

    /**
     * Hand-derived vector: n = 2^2047 + 1. Its lowest word is 1, so n0inv = -1 mod 2^32 = 0xffffffff.
     * 2^2047 ≡ -1 (mod n), so 2^4094 ≡ 1 and rr = 2^4096 mod n = 4.
     */
    @Test
    fun publicKeyStructMatchesHandDerivedVector() {
        val n = BigInteger.ONE.shiftLeft(2047).add(BigInteger.ONE)
        val s = ByteBuffer.wrap(AdbRsa.publicKeyStruct(n, e)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, s.capacity())
        assertEquals(64, s.int)
        assertEquals(-1, s.int) // n0inv
        val nWords = IntArray(64) { s.int }
        assertEquals(1, nWords[0])
        for (i in 1 until 63) assertEquals(0, nWords[i])
        assertEquals(0x80000000.toInt(), nWords[63])
        val rrWords = IntArray(64) { s.int }
        assertEquals(4, rrWords[0])
        for (i in 1 until 64) assertEquals(0, rrWords[i])
        assertEquals(65537, s.int)
    }

    @Test
    fun publicKeyOfRealKeySatisfiesMontgomeryProperties() {
        val key = newKey()
        val n = key.modulus
        val payload = AdbRsa.publicKeyPayload(n, key.publicExponent, "test@host")
        val text = String(payload, Charsets.US_ASCII)
        assertTrue(text.endsWith(" test@host\u0000"))
        val struct = Base64.getDecoder().decode(text.substringBefore(' '))
        val b = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val nBytes = ByteArray(256).also { b.get(it) }
        val rrBytes = ByteArray(256).also { b.get(it) }
        val decodedN = BigInteger(1, nBytes.reversedArray())
        val rr = BigInteger(1, rrBytes.reversedArray())
        assertEquals(n, decodedN)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        // n0inv * n0 ≡ -1 (mod 2^32)
        val n0 = n.toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (n0inv * n0) and 0xffffffffL)
        assertEquals(65537, b.int)
    }

    @Test
    fun signatureMatchesPkcs1Sha1DigestInfoSignature() {
        val key = newKey()
        val token = ByteArray(20) { (it * 13 + 7).toByte() }
        val ours = AdbRsa.sign(token, key.modulus, key.privateExponent)
        assertEquals(256, ours.size)
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token
        val jdk = Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(digestInfo)
            sign()
        }
        assertArrayEquals(jdk, ours)
    }

    private fun newKey(): RSAPrivateCrtKey = KeyPairGenerator.getInstance("RSA").run {
        initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        generateKeyPair().private as RSAPrivateCrtKey
    }
}
