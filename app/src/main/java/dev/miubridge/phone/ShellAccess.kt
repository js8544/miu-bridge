package dev.miubridge.phone

import java.io.Closeable
import java.io.IOException

/**
 * A way to run commands as Android's `shell` user: [AdbShell] reaches adbd on this phone, over
 * wireless debugging's TLS port or the plain one `adb tcpip 5555` opens.
 */
interface ShellAccess : Closeable {
    /** Runs [command] with `sh -c`, feeding [stdin] if given, and returns the combined output. */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 15_000): String
}

/** Nothing is listening where the shell should be (for adb: `adb tcpip` was not run, or the phone rebooted). */
class ShellUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The shell refused our key: the owner declined or did not answer the authorization dialog in time. */
class ShellNotAuthorizedException(message: String) : IOException(message)
