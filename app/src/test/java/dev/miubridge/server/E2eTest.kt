package dev.miubridge.server

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.SecretKeySpec

class E2eTest {
    private var token = "pb_test-token"
    private val e2e = E2e { token }

    /** The agent's side of a session, written out from the protocol's description. */
    private inner class Agent(private val token: String = this@E2eTest.token) {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private val pub = E2e.encode((pair.public as ECPublicKey).w)
        lateinit var sid: String
        private lateinit var c2s: SecretKeySpec
        private lateinit var s2c: SecretKeySpec

        fun hello(): E2e.Answer = runBlocking {
            val mac = E2e.hmac(token.toByteArray(), "miu-bridge e2e v1 hello".toByteArray() + pub)
            val answer = e2e.handle(JSONObject().put("miu_e2e", 1).put("hello", JSONObject().put("pub", E2e.b64(pub)).put("mac", E2e.b64(mac)))) { error("not reached") }
            if (answer.status == 200) {
                sid = answer.body.getString("sid")
                val theirs = E2e.unb64(answer.body.getString("pub"))!!
                val shared = KeyAgreement.getInstance("ECDH").apply {
                    init(pair.private); doPhase(E2e.publicKey(theirs, (pair.public as ECPublicKey).params), true)
                }.generateSecret()
                val keys = E2e.hkdf(token.toByteArray(), shared, "miu-bridge e2e v1".toByteArray() + pub + theirs + sid.toByteArray())
                c2s = SecretKeySpec(keys.copyOfRange(0, 32), "AES")
                s2c = SecretKeySpec(keys.copyOfRange(32, 64), "AES")
            }
            answer
        }

        fun seal(n: Long, body: String): JSONObject = JSONObject().put("miu_e2e", 1).put("sid", sid).put("n", n)
            .put("ct", E2e.b64(E2e.gcm(Cipher.ENCRYPT_MODE, c2s, n, "miu-e2e req $sid", body.toByteArray())))

        fun open(n: Long, answer: E2e.Answer): JSONObject =
            JSONObject(String(E2e.gcm(Cipher.DECRYPT_MODE, s2c, n, "miu-e2e res $sid", E2e.unb64(answer.body.getString("ct"))!!)))
    }

    private fun send(envelope: JSONObject, toolCall: Boolean = true): E2e.Answer = runBlocking {
        e2e.handle(envelope) { E2e.Inner(200, JSONObject().put("echo", String(it)), toolCall) }
    }

    @Test fun roundTrip() {
        val agent = Agent().apply { assertEquals(200, hello().status) }
        val answer = send(agent.seal(1, "tap"))
        assertEquals(200, answer.status)
        assertTrue(answer.metered)
        assertFalse(answer.body.toString().contains("tap"))
        val plain = agent.open(1, answer)
        assertEquals(200, plain.getInt("status"))
        assertEquals("tap", plain.getJSONObject("body").getString("echo"))
        assertFalse(send(agent.seal(2, "list"), toolCall = false).metered)
    }

    @Test fun wrongTokenGetsNoSession() {
        assertEquals(401, Agent("pb_other").hello().status)
    }

    @Test fun aMessageIsAcceptedOnce() {
        val agent = Agent().apply { hello() }
        val envelope = agent.seal(7, "tap")
        assertEquals(200, send(envelope).status)
        assertEquals("e2e_rejected", send(envelope).body.getString("code"))
        assertEquals(200, send(agent.seal(3, "earlier, arriving late")).status)
    }

    @Test fun oldCountersStayUsedAfterTheWindowMoves() {
        val agent = Agent().apply { hello() }
        val first = agent.seal(1, "tap")
        for (n in 1L..3000L) assertEquals(200, send(if (n == 1L) first else agent.seal(n, "x")).status)
        assertEquals(400, send(first).status)
    }

    @Test fun aChangedMessageIsRefused() {
        val agent = Agent().apply { hello() }
        val envelope = agent.seal(1, "tap")
        val ct = E2e.unb64(envelope.getString("ct"))!!.also { it[0] = (it[0] + 1).toByte() }
        assertEquals(400, send(JSONObject(envelope.toString()).put("ct", E2e.b64(ct))).status)
        assertEquals(400, send(JSONObject(envelope.toString()).put("n", 2)).status)
        assertEquals(200, send(envelope).status)
    }

    @Test fun replacingTheTokenEndsSessions() {
        val agent = Agent().apply { hello() }
        token = "pb_new"
        assertEquals("e2e_session", send(agent.seal(1, "tap")).body.getString("code"))
    }

    @Test fun pointsOffTheCurveAreRefused() {
        val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
        val good = E2e.encode(params.generator)
        assertTrue(E2e.publicKey(good, params) != null)
        assertTrue(E2e.publicKey(good.copyOf().also { it[64] = (it[64] + 1).toByte() }, params) == null)
        assertTrue(E2e.publicKey(good.copyOf(64), params) == null)
    }

    /** RFC 5869, test case 1, carried on to 64 bytes. */
    @Test fun hkdfMatchesTheRfc() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val out = E2e.hkdf(hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b }, hex("f0f1f2f3f4f5f6f7f8f9"))
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), out.copyOf(42))
    }
}
