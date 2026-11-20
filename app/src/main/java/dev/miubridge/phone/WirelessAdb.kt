package dev.miubridge.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * This phone's wireless debugging (Android 11+): adbd advertises its pairing and TLS ports over
 * mDNS on the Wi-Fi network, so the app finds its own adbd the way a computer would.
 */
object WirelessAdb {
    const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val SETTING = "adb_wifi_enabled"

    /** Whether this app has paired with wireless debugging (so the TLS port will accept our key). */
    fun isPaired(context: Context): Boolean = prefs(context).getBoolean("paired", false)

    fun markPaired(context: Context) = prefs(context).edit().putBoolean("paired", true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("wireless_adb", Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1

    /** True once the shell granted us WRITE_SECURE_SETTINGS, so we can switch wireless debugging back on after a reboot. */
    fun canSwitchOn(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Turns wireless debugging on. Android only does so on a Wi-Fi network the owner already allowed. */
    fun switchOn(context: Context): Boolean =
        canSwitchOn(context) && runCatching { Settings.Global.putInt(context.contentResolver, SETTING, 1) }.getOrDefault(false)

    /** Finds the port this phone's adbd advertises for [type], or null. Blocks up to [timeoutMs]. */
    fun findLocalPort(context: Context, type: String, timeoutMs: Long): Int? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val local = localAddresses()
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val started = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = started.countDown()
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = started.countDown()
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.offer(serviceInfo)
            }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                val service = found.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val resolved = resolve(nsd, service, 3_000) ?: continue
                // Other phones on the same Wi-Fi advertise too; only our own adbd will do.
                if (resolved.host?.hostAddress in local) return resolved.port
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService is the call that exists on every Android 11+ release
    private fun resolve(nsd: NsdManager, service: NsdServiceInfo, timeoutMs: Long): NsdServiceInfo? {
        val result = LinkedBlockingQueue<Result<NsdServiceInfo>>()
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                result.offer(Result.failure(IllegalStateException("resolve failed $errorCode")))
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                result.offer(Result.success(serviceInfo))
            }
        })
        return result.poll(timeoutMs, TimeUnit.MILLISECONDS)?.getOrNull()
    }

    private fun localAddresses(): Set<String> = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .toSet()
}
