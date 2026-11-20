package dev.miubridge.phone

import dev.miubridge.phone.AdbMessage.Companion.AUTH
import dev.miubridge.phone.AdbMessage.Companion.AUTH_RSAPUBLICKEY
import dev.miubridge.phone.AdbMessage.Companion.AUTH_SIGNATURE
import dev.miubridge.phone.AdbMessage.Companion.AUTH_TOKEN
import dev.miubridge.phone.AdbMessage.Companion.CLSE
import dev.miubridge.phone.AdbMessage.Companion.CNXN
import dev.miubridge.phone.AdbMessage.Companion.OKAY
import dev.miubridge.phone.AdbMessage.Companion.OPEN
import dev.miubridge.phone.AdbMessage.Companion.STLS
import dev.miubridge.phone.AdbMessage.Companion.WRTE
import org.bouncycastle.tls.TlsFatalAlertReceived
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A minimal ADB host speaking to adbd over TCP: the legacy port (`adb tcpip`, RSA auth) or the
 * wireless-debugging TLS port (STLS upgrade, our key must have been paired). It runs one `exec:`
 * stream at a time; that is all the phone layer needs to start its helper.
 */
class AdbShell private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val maxData: Int,
    private val tls: AdbTls?,
) : ShellAccess {
    private var nextLocalId = 1

    @Synchronized
    override fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val localId = nextLocalId++
        send(socket, output, AdbMessage(OPEN, localId, 0, "exec:$command\u0000".toByteArray(Charsets.UTF_8)))

        var remoteId = 0
        while (remoteId == 0) {
            val m = read(deadline)
            if (m.arg1 != localId) continue
            when (m.command) {
                OKAY -> remoteId = m.arg0
                CLSE -> throw IOException("adbd refused the exec stream")
            }
        }

        val chunks = stdin?.let { s -> (s.indices step maxData).map { s.copyOfRange(it, minOf(it + maxData, s.size)) } }.orEmpty().toMutableList()
        var awaitingAck = false
        fun sendNextChunk() {
            if (chunks.isNotEmpty()) {
                send(socket, output, AdbMessage(WRTE, localId, remoteId, chunks.removeAt(0)))
                awaitingAck = true
            }
        }
        sendNextChunk()

        val out = ByteArrayOutputStream()
        while (true) {
            val m = read(deadline)
            if (m.arg1 != localId) continue
            when (m.command) {
                OKAY -> if (awaitingAck) { awaitingAck = false; sendNextChunk() }
                WRTE -> {
                    if (out.size() < MAX_OUTPUT) out.write(m.data, 0, minOf(m.data.size, MAX_OUTPUT - out.size()))
                    send(socket, output, AdbMessage(OKAY, localId, remoteId))
                }
                CLSE -> {
                    send(socket, output, AdbMessage(CLSE, localId, remoteId))
                    return out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    override fun close() {
        tls?.close()
        runCatching { socket.close() }
    }

    private fun read(deadlineNanos: Long): AdbMessage {
        val left = (deadlineNanos - System.nanoTime()) / 1_000_000
        if (left <= 0) throw SocketTimeoutException("adb command timed out")
        socket.soTimeout = left.toInt().coerceAtLeast(1)
        return AdbMessage.read(input, maxOf(maxData, AdbMessage.MAX_DATA))
    }

    companion object {
        private const val MAX_OUTPUT = 1 shl 20
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /**
         * Connects and authenticates. Signs adbd's token with [key]; if adbd does not know the key yet,
         * offers the public key, calls [onApprovalNeeded] (the phone now shows "Allow USB debugging?")
         * and waits up to [approvalTimeoutMs] for the owner.
         */
        fun connect(
            host: String,
            port: Int,
            key: AdbKey,
            keyName: String,
            approvalTimeoutMs: Int = 60_000,
            onApprovalNeeded: () -> Unit,
        ): AdbShell {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 3_000)
            } catch (e: IOException) {
                socket.close()
                throw ShellUnavailableException("adbd is not listening on $host:$port", e)
            }
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                var input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                var output = socket.getOutputStream()
                var tls: AdbTls? = null
                send(socket, output, AdbMessage(CNXN, AdbMessage.VERSION, AdbMessage.MAX_DATA, "host::\u0000".toByteArray()))
                var sentSignature = false
                var sentPublicKey = false
                while (true) {
                    val m = try {
                        AdbMessage.read(input)
                    } catch (e: IOException) {
                        // TLS 1.3: adbd checks our certificate after the handshake and answers an
                        // unknown key with an alert on the first read.
                        if (tls != null && e is TlsFatalAlertReceived) {
                            throw ShellNotAuthorizedException("adbd does not know our key (pair again)")
                        }
                        if (sentPublicKey && (e is SocketTimeoutException || e is EOFException)) {
                            throw ShellNotAuthorizedException("adb authorization was not granted")
                        }
                        throw e
                    }
                    when (m.command) {
                        CNXN -> return AdbShell(socket, input, output, m.arg1.coerceIn(4096, AdbMessage.MAX_DATA), tls)
                        STLS -> {
                            // Wireless debugging: switch to TLS; adbd authorizes our paired key there.
                            send(socket, output, AdbMessage(STLS, AdbMessage.STLS_VERSION, 0))
                            tls = try {
                                AdbTls.handshake(socket, key.key)
                            } catch (e: IOException) {
                                throw ShellNotAuthorizedException("adbd did not accept our key over TLS (not paired?)")
                            }
                            input = DataInputStream(tls.input)
                            output = tls.output
                        }
                        AUTH -> {
                            if (m.arg0 != AUTH_TOKEN) throw IOException("unexpected adb auth type ${m.arg0}")
                            when {
                                !sentSignature -> {
                                    send(socket, output, AdbMessage(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data)))
                                    sentSignature = true
                                }
                                !sentPublicKey -> {
                                    send(socket, output, AdbMessage(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(keyName)))
                                    sentPublicKey = true
                                    socket.soTimeout = approvalTimeoutMs
                                    onApprovalNeeded()
                                }
                                else -> throw ShellNotAuthorizedException("adbd rejected our key")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }

        private fun send(socket: Socket, output: OutputStream, message: AdbMessage) {
            if (socket.isClosed) throw IOException("adb connection closed")
            output.write(message.encode())
            output.flush()
        }
    }
}
