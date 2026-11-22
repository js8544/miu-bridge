package dev.miubridge.server

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption between an agent and this phone, so that whatever carries the request (the
 * relay, the Wi-Fi) sees neither the token nor what is asked and answered, and cannot change or
 * repeat it. The token is the shared secret and is never sent.
 *
 * A request body that is a JSON object with `"miu_e2e": 1` is an envelope:
 *
 *   hello    {miu_e2e, hello: {pub, mac}}   -> {miu_e2e, sid, pub}
 *   request  {miu_e2e, sid, n, ct}          -> {miu_e2e, ct}
 *
 * hello: `pub` is the agent's fresh P-256 key (65 bytes, uncompressed) and `mac` is
 * HMAC-SHA256(token, "miu-bridge e2e v1 hello" ‖ pub), so only a holder of the token can open a
 * session. The phone answers with a fresh key of its own and a session id. Both sides derive
 * HKDF-SHA256(salt = token, ikm = ECDH x, info = "miu-bridge e2e v1" ‖ agent pub ‖ phone pub ‖ sid)
 * into 64 bytes: an AES-256-GCM key for each direction. Both keys are fresh, so traffic recorded
 * today stays closed to someone who learns the token later.
 *
 * request: `n` is a counter the agent raises with every message, `ct` the JSON-RPC body sealed with
 * the agent-to-phone key, nonce 0x00000000 ‖ n (8 bytes, big-endian), associated data
 * "miu-e2e req " ‖ sid. A counter is accepted once. The answer is `{status, body}` sealed with the
 * phone-to-agent key under the same nonce and "miu-e2e res " ‖ sid, which ties it to its request.
 *
 * Bytes are standard base64. Sessions live in memory: after a restart, or when a session has been
 * pushed out by newer ones, the phone answers `e2e_session` and the agent says hello again.
 */
class E2e(private val token: () -> String) {
    /** What goes back on the wire. [metered] tells the relay the request inside was a tool call that was answered. */
    class Answer(val status: Int, val body: JSONObject, val metered: Boolean = false)

    /** What the request inside came to. */
    class Inner(val status: Int, val body: JSONObject?, val toolCall: Boolean = false)

    private class Session(val token: String, c2s: ByteArray, s2c: ByteArray) {
        val c2s = SecretKeySpec(c2s, "AES")
        val s2c = SecretKeySpec(s2c, "AES")
        /** Counters at or below [floor], and those in [seen], have been used. */
        var floor = 0L
        val seen = sortedSetOf<Long>()
    }

    private val random = SecureRandom()
    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) = size > MAX_SESSIONS
    }

    suspend fun handle(envelope: JSONObject, inner: suspend (ByteArray) -> Inner): Answer {
        envelope.optJSONObject("hello")?.let { return hello(it) }
        val sid = envelope.optString("sid")
        val n = envelope.optLong("n", 0)
        val session = synchronized(sessions) { sessions[sid]?.takeIf { it.token == token() } ?: run { sessions.remove(sid); null } }
            ?: return refuse(409, "e2e_session", "no such session; say hello again")
        val request = open(session, sid, n, envelope.optString("ct"))
            ?: return refuse(400, "e2e_rejected", "the message could not be opened, or was already used")
        val reply = inner(request)
        val plain = JSONObject().put("status", reply.status).put("body", reply.body ?: JSONObject.NULL)
        val ct = gcm(Cipher.ENCRYPT_MODE, session.s2c, n, "$AAD_RESPONSE$sid", plain.toString().toByteArray())
        return Answer(200, JSONObject().put("miu_e2e", 1).put("ct", b64(ct)), metered = reply.toolCall && reply.status == 200)
    }

    private fun hello(hello: JSONObject): Answer {
        val token = token()
        val theirs = unb64(hello.optString("pub"))
        val mac = unb64(hello.optString("mac"))
        // Before any curve arithmetic: someone without the token gets no work out of the phone.
        if (theirs == null || mac == null || !MessageDigest.isEqual(mac, hmac(token.toByteArray(), HELLO.toByteArray() + theirs))) {
            return refuse(401, "unauthorized", "unauthorized")
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val ours = (pair.public as ECPublicKey)
        val peer = publicKey(theirs, ours.params) ?: return refuse(400, "e2e_rejected", "bad key")
        val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(peer, true) }.generateSecret()
        val pub = encode(ours.w)
        val sid = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val keys = hkdf(token.toByteArray(), shared, INFO.toByteArray() + theirs + pub + sid.toByteArray())
        synchronized(sessions) { sessions[sid] = Session(token, keys.copyOfRange(0, 32), keys.copyOfRange(32, 64)) }
        return Answer(200, JSONObject().put("miu_e2e", 1).put("sid", sid).put("pub", b64(pub)))
    }

    /** The request inside, or null when it is not authentic or its counter was used before. */
    private fun open(session: Session, sid: String, n: Long, ct: String): ByteArray? {
        if (n <= 0) return null
        val bytes = unb64(ct) ?: return null
        val plain = runCatching { gcm(Cipher.DECRYPT_MODE, session.c2s, n, "$AAD_REQUEST$sid", bytes) }.getOrNull() ?: return null
        // Only an authentic message marks its counter, so a forger cannot use counters up.
        synchronized(session) {
            if (n <= session.floor || !session.seen.add(n)) return null
            if (session.seen.size > WINDOW) repeat(WINDOW / 2) { session.floor = session.seen.pollFirst()!! }
        }
        return plain
    }

    private fun refuse(status: Int, code: String, message: String) =
        Answer(status, JSONObject().put("miu_e2e", 1).put("error", message).put("code", code))

    companion object {
        private const val HELLO = "miu-bridge e2e v1 hello"
        private const val INFO = "miu-bridge e2e v1"
        private const val AAD_REQUEST = "miu-e2e req "
        private const val AAD_RESPONSE = "miu-e2e res "
        private const val MAX_SESSIONS = 32
        private const val WINDOW = 2048

        /** Whether a request body is an envelope rather than plain JSON-RPC. */
        fun isEnvelope(body: JSONObject) = body.optInt("miu_e2e") == 1

        internal fun gcm(mode: Int, key: SecretKeySpec, n: Long, aad: String, input: ByteArray): ByteArray {
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[11 - i] = (n ushr (8 * i)).toByte()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, key, GCMParameterSpec(128, nonce))
                updateAAD(aad.toByteArray())
                doFinal(input)
            }
        }

        internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** HKDF-SHA256 (RFC 5869), 64 bytes out. */
        internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray): ByteArray {
            val prk = hmac(salt, ikm)
            val t1 = hmac(prk, info + 1)
            return t1 + hmac(prk, t1 + info + 2)
        }

        /** An uncompressed P-256 point as a key, or null unless it is a point on the curve. */
        internal fun publicKey(raw: ByteArray, params: ECParameterSpec): PublicKey? {
            if (raw.size != 65 || raw[0] != 4.toByte()) return null
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            val p = (params.curve.field as java.security.spec.ECFieldFp).p
            if (x >= p || y >= p) return null
            val right = (x.pow(3) + params.curve.a * x + params.curve.b).mod(p)
            if (y.pow(2).mod(p) != right) return null
            return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) }.getOrNull()
        }

        internal fun encode(w: ECPoint): ByteArray = byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)

        private fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
        }

        internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        internal fun unb64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
