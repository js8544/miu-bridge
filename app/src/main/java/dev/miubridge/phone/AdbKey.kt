package dev.miubridge.phone

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * The app's persistent RSA-2048 identity towards adbd, stored as PKCS#8 in app-private storage.
 * Parsed and generated with BouncyCastle rather than the platform KeyFactory: on some phones
 * (HarmonyOS 4) the platform returns a private key without its CRT parts, and a key that cannot be
 * read back would be replaced by a new one that adbd has never seen.
 */
class AdbKey private constructor(internal val key: RSAPrivateCrtKeyParameters) {

    fun sign(token: ByteArray): ByteArray = AdbRsa.sign(token, key.modulus, key.exponent)

    fun publicKeyPayload(name: String): ByteArray = AdbRsa.publicKeyPayload(key.modulus, key.publicExponent, name)

    companion object {
        /** The name adbd shows for our key in its list of authorized computers. */
        const val NAME = "miubridge"

        fun of(context: android.content.Context): AdbKey = loadOrCreate(File(context.filesDir, "adb_key.pk8"))

        fun loadOrCreate(file: File): AdbKey {
            if (file.exists()) {
                runCatching { return AdbKey(PrivateKeyFactory.createKey(file.readBytes()) as RSAPrivateCrtKeyParameters) }
            }
            val generator = RSAKeyPairGenerator()
            generator.init(RSAKeyGenerationParameters(BigInteger.valueOf(65537), SecureRandom(), 2048, 100))
            val key = generator.generateKeyPair().private as RSAPrivateCrtKeyParameters
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(PrivateKeyInfoFactory.createPrivateKeyInfo(key).encoded)
            if (!tmp.renameTo(file)) throw java.io.IOException("could not store adb key")
            return AdbKey(key)
        }
    }
}

/** The RSA pieces of adb authentication, kept pure for tests. */
object AdbRsa {
    private const val MODULUS_BYTES = 256

    /** ASN.1 DigestInfo prefix for SHA-1; adbd verifies the token as if it were a SHA-1 digest. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** PKCS#1 v1.5 signature over DigestInfo(SHA-1, token), big-endian, modulus-sized. */
    fun sign(token: ByteArray, n: BigInteger, d: BigInteger): ByteArray {
        require(token.size == 20) { "adb auth token must be 20 bytes" }
        val k = (n.bitLength() + 7) / 8
        val tLen = SHA1_DIGEST_INFO.size + token.size
        val em = ByteArray(k)
        em[1] = 0x01
        for (i in 2 until k - tLen - 1) em[i] = 0xff.toByte()
        SHA1_DIGEST_INFO.copyInto(em, k - tLen)
        token.copyInto(em, k - token.size)
        return toFixedBigEndian(BigInteger(1, em).modPow(d, n), k)
    }

    /**
     * Android's adb public key: base64 of the mincrypt `RSAPublicKey` struct
     * `{u32 len (words), u32 n0inv, u32 n[64], u32 rr[64], u32 e}` (all little-endian,
     * least significant word first), then " name" and a NUL.
     */
    fun publicKeyPayload(n: BigInteger, e: BigInteger, name: String): ByteArray =
        (Base64.getEncoder().encodeToString(publicKeyStruct(n, e)) + " " + name + "\u0000").toByteArray(Charsets.US_ASCII)

    fun publicKeyStruct(n: BigInteger, e: BigInteger): ByteArray {
        require(n.bitLength() == MODULUS_BYTES * 8) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n)
        return ByteBuffer.allocate(4 + 4 + MODULUS_BYTES * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_BYTES / 4)
            .putInt(n0inv.toInt())
            .put(toFixedBigEndian(n, MODULUS_BYTES).reversedArray())
            .put(toFixedBigEndian(rr, MODULUS_BYTES).reversedArray())
            .putInt(e.toInt())
            .array()
    }

    private fun toFixedBigEndian(x: BigInteger, size: Int): ByteArray {
        val raw = x.toByteArray()
        val start = (raw.size - size).coerceAtLeast(0)
        val out = ByteArray(size)
        raw.copyInto(out, size - (raw.size - start), start, raw.size)
        return out
    }
}
