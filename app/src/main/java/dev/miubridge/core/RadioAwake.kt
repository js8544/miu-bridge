package dev.miubridge.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address

/**
 * Keeps the Wi-Fi radio out of power save while an agent is at work. An idle radio sleeps after
 * about 200 ms and then takes what arrives only at its next wake-up, so a request that came more
 * than a moment after the last one waited 50 to 100 ms at the access point before the phone saw it.
 * A radio that keeps sending does not go to sleep: for as long as [Awake.serving] holds, one byte
 * goes to the Wi-Fi gateway every [EVERY_MS]. It never leaves the local network and nobody answers.
 * Android's low-latency Wi-Fi lock does this only while the screen is on. Not on mobile data.
 */
internal object RadioAwake {
    private const val EVERY_MS = 100L
    private const val DISCARD_PORT = 9
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun whileServing(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return@launch
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@launch
            val gateway = connectivity.getLinkProperties(network)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway ?: return@launch
            runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    val packet = DatagramPacket(ByteArray(1), 1, gateway, DISCARD_PORT)
                    while (Awake.serving) {
                        socket.send(packet)
                        delay(EVERY_MS)
                    }
                }
            }
        }
    }
}
