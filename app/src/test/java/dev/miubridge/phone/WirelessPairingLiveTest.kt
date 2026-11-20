package dev.miubridge.phone

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pairs a brand-new key with a real adbd and then opens a shell over TLS. Skipped unless
 * PB_PAIR_PORT, PB_PAIR_CODE and PB_TLS_PORT point at a device's wireless debugging (e.g. through
 * `adb forward`).
 */
class WirelessPairingLiveTest {
    @Test fun pairsThenConnectsOverTls() {
        val pairPort = System.getenv("PB_PAIR_PORT")?.toIntOrNull()
        val code = System.getenv("PB_PAIR_CODE")
        val tlsPort = System.getenv("PB_TLS_PORT")?.toIntOrNull()
        assumeTrue(pairPort != null && code != null && tlsPort != null)
        val file = File(System.getenv("PB_KEY_FILE") ?: File.createTempFile("pb-test-key", ".pk8").also { it.delete() }.path)
        val key = AdbKey.loadOrCreate(file)

        try {
            AdbShell.connect("127.0.0.1", tlsPort!!, key, "pb-jvm-test") {}.close()
            fail("an unpaired key must not be accepted")
        } catch (e: ShellNotAuthorizedException) {
            println("before pairing: refused as expected")
        }

        AdbPairing.pair("127.0.0.1", pairPort!!, code!!, key, "pb-jvm-test")
        println("paired")

        // Android stores the newly paired key a moment after pairing finishes.
        val shell = (1..10).firstNotNullOfOrNull {
            Thread.sleep(1_000)
            runCatching { AdbShell.connect("127.0.0.1", tlsPort!!, key, "pb-jvm-test") {} }
                .onFailure { e -> println("connect attempt $it: ${e.message}") }.getOrNull()
        } ?: throw AssertionError("never accepted after pairing")
        shell.use { shell ->
            val out = shell.exec("id -u; getprop ro.build.version.sdk")
            println("shell says: ${out.trim().replace('\n', ' ')}")
            assertTrue(out.startsWith("2000"))
        }
        file.delete()
    }
}
