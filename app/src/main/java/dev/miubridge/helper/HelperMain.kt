package dev.miubridge.helper

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Entry point of the shell-user helper, started by the app through adb as
 * `CLASSPATH=<apk> app_process /system/bin dev.miubridge.helper.HelperMain <port>` with the
 * secret on stdin-derived env `PB_SECRET`. It listens on 127.0.0.1:<port> for the app only.
 */
object HelperMain {
    internal const val TAG = "MiuBridgeHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull()
        val secret = System.getenv("PB_SECRET")?.takeIf { it.length >= 32 }
        if (port == null || secret == null) {
            System.err.println("usage: PB_SECRET=<secret> HelperMain <port>")
            exitProcess(2)
        }
        Looper.prepareMainLooper()
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            }
        } catch (e: IOException) {
            Log.e(TAG, "cannot listen on port $port")
            exitProcess(1)
        }
        val host = HelperHost(Handler(Looper.getMainLooper()), System.getenv("CLASSPATH").orEmpty())
        Thread({ acceptLoop(server, secret.toByteArray(), host) }, "pb-accept").start()
        Log.i(TAG, "helper listening (pid ${android.os.Process.myPid()})")
        Looper.loop()
    }

    private fun acceptLoop(server: ServerSocket, secret: ByteArray, host: HelperHost) {
        while (true) {
            val socket = try { server.accept() } catch (e: IOException) { exitProcess(1) }
            Thread({ serve(socket, secret, host) }, "pb-client").start()
        }
    }

    private fun serve(socket: Socket, secret: ByteArray, host: HelperHost) = socket.use {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            if (!HelperWire.serverHandshake(input, out, secret)) {
                Log.w(TAG, "rejected an unauthenticated client")
                return@use
            }
            socket.soTimeout = 0
            while (true) {
                val request = JSONObject(String(HelperWire.readFrame(input), Charsets.UTF_8))
                val (reply, bytes) = host.handle(request)
                HelperWire.writeFrame(out, reply.toString().toByteArray(Charsets.UTF_8))
                if (bytes != null) HelperWire.writeFrame(out, bytes)
                if (request.optString("op") == "quit") {
                    host.shutdown()
                    exitProcess(0)
                }
            }
        } catch (e: EOFException) {
            // client went away
        } catch (e: IOException) {
            Log.w(TAG, "client connection ended: ${e.javaClass.simpleName}")
        }
    }
}
