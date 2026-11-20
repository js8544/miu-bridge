package dev.miubridge.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The app ↔ helper wire: frames of `[u32 big-endian length][bytes]` over loopback TCP, opened by a
 * mutual HMAC-SHA256 challenge on the per-launch secret. Requests and replies are JSON frames; a
 * reply with `"bin": true` is followed by one binary frame.
 */
object HelperWire {
    private const val MAX_FRAME = 32 shl 20
    private const val NONCE = 16
    private val random = SecureRandom()

    fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > MAX_FRAME) throw IOException("bad frame size")
        return ByteArray(size).also { input.readFully(it) }
    }

    /** Server: send a challenge, check the client's proof, then prove ourselves. False if the client is not ours. */
    fun serverHandshake(input: DataInputStream, out: DataOutputStream, secret: ByteArray): Boolean {
        val serverNonce = ByteArray(NONCE).also(random::nextBytes)
        writeFrame(out, serverNonce)
        val answer = readFrame(input)
        if (answer.size != 32 + NONCE) return false
        if (!MessageDigest.isEqual(answer.copyOfRange(0, 32), mac(secret, "app", serverNonce))) return false
        writeFrame(out, mac(secret, "helper", answer.copyOfRange(32, 32 + NONCE)))
        return true
    }

    /** Client: answer the challenge and require the server to know the secret too. */
    fun clientHandshake(input: DataInputStream, out: DataOutputStream, secret: ByteArray) {
        val serverNonce = readFrame(input)
        if (serverNonce.size != NONCE) throw IOException("bad helper challenge")
        val clientNonce = ByteArray(NONCE).also(random::nextBytes)
        writeFrame(out, mac(secret, "app", serverNonce) + clientNonce)
        if (!MessageDigest.isEqual(readFrame(input), mac(secret, "helper", clientNonce))) {
            throw IOException("helper failed authentication")
        }
    }

    private fun mac(secret: ByteArray, label: String, nonce: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            update(label.toByteArray())
            doFinal(nonce)
        }
}
