package dev.miubridge.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class HelperWireTest {
    private fun handshake(serverSecret: String, clientSecret: String): Pair<Boolean, Throwable?> {
        val toServer = PipedOutputStream()
        val serverIn = DataInputStream(PipedInputStream(toServer, 1 shl 16))
        val toClient = PipedOutputStream()
        val clientIn = DataInputStream(PipedInputStream(toClient, 1 shl 16))
        var accepted = false
        val server = thread {
            accepted = runCatching { HelperWire.serverHandshake(serverIn, DataOutputStream(toClient), serverSecret.toByteArray()) }.getOrDefault(false)
            if (!accepted) toClient.close()
        }
        val clientError = runCatching { HelperWire.clientHandshake(clientIn, DataOutputStream(toServer), clientSecret.toByteArray()) }.exceptionOrNull()
        server.join(5_000)
        return accepted to clientError
    }

    @Test
    fun sameSecretAuthenticatesBothSides() {
        val (accepted, error) = handshake("a".repeat(64), "a".repeat(64))
        assertTrue(accepted)
        assertTrue(error == null)
    }

    @Test
    fun wrongSecretIsRejected() {
        val (accepted, error) = handshake("a".repeat(64), "b".repeat(64))
        assertFalse(accepted)
        assertTrue(error is IOException)
    }
}
