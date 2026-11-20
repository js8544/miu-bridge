package dev.miubridge.phone

import dev.miubridge.helper.HelperMain
import dev.miubridge.helper.HelperWire
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** The helper answered but refused or failed the request; the message is safe to show. */
class HelperError(message: String) : Exception(message)

/** The app's authenticated connection to the shell-user helper. IOException means the helper is gone. */
internal class HelperClient private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    class Reply(val json: JSONObject, val bytes: ByteArray?)

    @Synchronized
    fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = 15_000): Reply {
        val request = JSONObject(args.toString()).put("op", op)
        socket.soTimeout = timeoutMs.toInt()
        HelperWire.writeFrame(output, request.toString().toByteArray(Charsets.UTF_8))
        val json = JSONObject(String(HelperWire.readFrame(input), Charsets.UTF_8))
        val bytes = if (json.optBoolean("bin")) HelperWire.readFrame(input) else null
        if (!json.optBoolean("ok")) throw HelperError(json.optString("error", "helper request failed"))
        return Reply(json, bytes)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    /** Where a launched helper listens and the secret it accepts; persisted so a restarted app can reuse it. */
    class Session(val port: Int, val secret: String) {
        fun save(file: File) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("port", port).put("secret", secret).toString())
            tmp.renameTo(file)
        }

        companion object {
            fun load(file: File): Session? = runCatching {
                val j = JSONObject(file.readText())
                Session(j.getInt("port"), j.getString("secret"))
            }.getOrNull()

            fun fresh(): Session {
                val random = SecureRandom()
                val bytes = ByteArray(32).also(random::nextBytes)
                return Session(40_000 + random.nextInt(10_000), bytes.joinToString("") { "%02x".format(it) })
            }
        }
    }

    companion object {
        fun connect(session: Session): HelperClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1", session.port), 1_500)
                socket.tcpNoDelay = true
                socket.soTimeout = 5_000
                val client = HelperClient(socket)
                HelperWire.clientHandshake(client.input, client.output, session.secret.toByteArray())
                return client
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }

        /**
         * Shell command that stops any stale helper, then starts a new one detached from the adb
         * stream. The secret is read from stdin so it never appears on a command line. The trailing
         * sleep lets the helper finish detaching: on HarmonyOS 4 a shell that exits at once takes the
         * just-forked helper down with it.
         */
        fun launchCommand(apk: String, port: Int): String {
            val main = HelperMain::class.java.name
            val pkg = main.substringBeforeLast('.')
            val cls = main.substringAfterLast('.')
            // The class name is split so pkill's pattern cannot match this shell's own command line.
            return "P=$pkg; C=$cls; pkill -f '$pkg.${cls.dropLast(1)}[${cls.last()}]'; " +
                "read -r PB_SECRET; export PB_SECRET; " +
                "if command -v setsid >/dev/null 2>&1; then S=setsid; else S=nohup; fi; " +
                "CLASSPATH=${shellQuote(apk)} \$S app_process /system/bin \"\$P.\$C\" $port </dev/null >/dev/null 2>&1 & sleep 1"
        }

        private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
