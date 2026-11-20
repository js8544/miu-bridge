package dev.miubridge.phone

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The pairing code was wrong (or expired); the owner should try again. */
class PairingCodeException : IOException("pairing code rejected")

/**
 * Pairs our adb key with this phone's wireless debugging ("Pair device with pairing code"), the way
 * `adb pair` does: TLS 1.3, SPAKE2 keyed by the code and TLS-exported material, then an
 * AES-128-GCM exchange of public keys. Afterwards adbd trusts our key on its TLS port.
 */
object AdbPairing {
    private const val VERSION = 1
    private const val TYPE_SPAKE2_MSG = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_INFO_SIZE = 8192
    private const val PEER_RSA_PUB_KEY = 0

    fun pair(host: String, port: Int, code: String, key: AdbKey, keyName: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 15_000
            socket.tcpNoDelay = true
            val tls = AdbTls.handshake(socket, key.key)
            try {
                val input = DataInputStream(tls.input)
                val out = tls.output
                val password = code.trim().toByteArray(Charsets.US_ASCII) + tls.exportedKeyMaterial
                val spake = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME, password)

                writePacket(out, TYPE_SPAKE2_MSG, spake.message)
                val theirMsg = readPacket(input, TYPE_SPAKE2_MSG) ?: throw PairingCodeException()
                val cipher = PairingCipher(spake.process(theirMsg))

                val info = ByteArray(PEER_INFO_SIZE)
                info[0] = PEER_RSA_PUB_KEY.toByte()
                val pub = key.publicKeyPayload(keyName) // ends with NUL
                pub.copyInto(info, 1, 0, minOf(pub.size, PEER_INFO_SIZE - 1))
                writePacket(out, TYPE_PEER_INFO, cipher.encrypt(info))

                // A wrong code makes the two sides derive different keys: adbd cannot decrypt our
                // info and hangs up, or we cannot decrypt its.
                val theirInfo = readPacket(input, TYPE_PEER_INFO) ?: throw PairingCodeException()
                runCatching { cipher.decrypt(theirInfo) }.getOrElse { throw PairingCodeException() }
            } finally {
                tls.close()
            }
        }
    }

    private fun writePacket(out: java.io.OutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte()).put(type.toByte()).putInt(payload.size).array()
        out.write(header + payload)
        out.flush()
    }

    /** Returns null when the peer closed the connection (how adbd answers a wrong code). */
    private fun readPacket(input: DataInputStream, expectedType: Int): ByteArray? {
        val header = ByteArray(6)
        try {
            input.readFully(header)
        } catch (e: IOException) {
            return null
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt()
        val type = buf.get().toInt()
        val size = buf.int
        if (version != VERSION || type != expectedType || size <= 0 || size > PEER_INFO_SIZE * 2) {
            throw IOException("unexpected pairing packet (version $version, type $type, size $size)")
        }
        return ByteArray(size).also { input.readFully(it) }
    }

    /** AES-128-GCM keyed by HKDF-SHA256 of the SPAKE2 key; nonces are per-direction little-endian counters. */
    internal class PairingCipher(keyMaterial: ByteArray) {
        private val key: SecretKeySpec
        private var encSeq = 0L
        private var decSeq = 0L

        init {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)))
            key = SecretKeySpec(ByteArray(16).also { hkdf.generateBytes(it, 0, 16) }, "AES")
        }

        fun encrypt(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, encSeq++, plain)

        fun decrypt(sealed: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, decSeq++, sealed)

        private fun run(mode: Int, seq: Long, data: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(mode, key, GCMParameterSpec(128, nonce))
            return c.doFinal(data)
        }
    }
}
