package dev.miubridge.phone

import java.io.Closeable
import java.io.IOException

/**
 * A way to run commands as Android's `shell` user. Today [AdbShell] reaches adbd over loopback TCP
 * (after `adb tcpip 5555`); TLS wireless-debugging pairing can implement this interface later.
 */
interface ShellAccess : Closeable {
    /** Runs [command] with `sh -c`, feeding [stdin] if given, and returns the combined output. */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 15_000): String
}

/** Nothing is listening where the shell should be (for adb: `adb tcpip` was not run, or the phone rebooted). */
class ShellUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The shell refused our key: the owner declined or did not answer the authorization dialog in time. */
class ShellNotAuthorizedException(message: String) : IOException(message)
