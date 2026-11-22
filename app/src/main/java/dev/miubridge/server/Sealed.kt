package dev.miubridge.server

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of request and answer bodies, so whatever carries them between the agent
 * and this phone (the relay, or plain HTTP on the local network) sees neither the contents nor the
 * access token. The key is derived from the token both ends already hold, so there is no handshake
 * and no extra round trip.
 *
 * A sealed body is the text `e1.` + base64url(nonce ‖ AES-256-GCM(key, nonce, aad, plain)).
 * A request's plain text is its sending time (8 bytes, ms) ‖ packing ‖ body, with no aad; an
 * answer's is packing ‖ body, bound to its request by using the request's nonce as aad. Packing is
 * 1 when the body is deflated, which makes up for what base64 adds.
 */
internal object Sealed {
    const val PREFIX = "e1."

    class Request(val body: ByteArray, val nonce: ByteArray)

    /** The request has the right key but was sent too long ago, or was already seen: a replay. */
    class Stale : Exception()

    fun isSealed(raw: ByteArray): Boolean = raw.size > PREFIX.length && String(raw, 0, PREFIX.length, Charsets.US_ASCII) == PREFIX

    /** The request's body, or null when it was not sealed with [token]'s key. */
    fun open(token: String, raw: ByteArray, now: Long = System.currentTimeMillis()): Request? {
        val packed = runCatching { Base64.decode(String(raw, Charsets.US_ASCII).substring(PREFIX.length), Base64.URL_SAFE) }.getOrNull() ?: return null
        if (packed.size < NONCE + TAG + 9) return null
        val nonce = packed.copyOf(NONCE)
        val plain = runCatching { cipher(Cipher.DECRYPT_MODE, token, nonce, null).doFinal(packed, NONCE, packed.size - NONCE) }.getOrNull() ?: return null
        val sentAt = ByteBuffer.wrap(plain, 0, 8).long
        val mark = Base64.encodeToString(nonce, Base64.NO_WRAP)
        synchronized(seen) {
            seen.values.removeAll { now - it > WINDOW_MS }
            if (Math.abs(now - sentAt) > WINDOW_MS || seen.put(mark, now) != null) throw Stale()
        }
        return Request(unpack(plain, 8), nonce)
    }

    fun seal(token: String, body: ByteArray, request: ByteArray): String {
        val nonce = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
        val sealed = cipher(Cipher.ENCRYPT_MODE, token, nonce, request).doFinal(pack(body))
        return PREFIX + Base64.encodeToString(nonce + sealed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun cipher(mode: Int, token: String, nonce: ByteArray, aad: ByteArray?): Cipher {
        val key = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
            doFinal(LABEL.toByteArray())
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            aad?.let { updateAAD(it) }
        }
    }

    private fun pack(body: ByteArray): ByteArray {
        if (body.size < 1024) return byteArrayOf(0) + body
        val deflater = Deflater(Deflater.BEST_SPEED, true).apply { setInput(body); finish() }
        val out = ByteArrayOutputStream(body.size / 2).apply { write(1) }
        val chunk = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    private fun unpack(plain: ByteArray, from: Int): ByteArray {
        if (plain[from].toInt() == 0) return plain.copyOfRange(from + 1, plain.size)
        val inflater = Inflater(true).apply { setInput(plain, from + 1, plain.size - from - 1) }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_BODY) break
        }
        inflater.end()
        return out.toByteArray()
    }

    private val seen = HashMap<String, Long>()
    private const val LABEL = "miu-bridge e2e v1"
    private const val NONCE = 12
    private const val TAG = 16
    /** How far apart the two clocks may be, and how long a request's nonce is remembered. */
    private const val WINDOW_MS = 5 * 60_000L
    private const val MAX_BODY = 4 * 1024 * 1024
}
