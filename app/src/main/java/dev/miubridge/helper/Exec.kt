package dev.miubridge.helper

import java.util.concurrent.TimeUnit

/** Runs a program (no shell parsing) as the helper's user, with a timeout and capped output. */
internal object Exec {
    class Result(val code: Int, val out: String)

    private const val MAX_OUTPUT = 256 * 1024

    fun run(argv: List<String>, timeoutMs: Long): Result {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        val buffer = java.io.ByteArrayOutputStream()
        val reader = Thread {
            val chunk = ByteArray(8192)
            process.inputStream.use { stream ->
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (buffer.size() < MAX_OUTPUT) buffer.write(chunk, 0, minOf(n, MAX_OUTPUT - buffer.size()))
                }
            }
        }.apply { start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw HelperFailure("${argv.firstOrNull()} timed out")
        }
        reader.join(1_000)
        return Result(process.exitValue(), synchronized(buffer) { buffer.toString(Charsets.UTF_8.name()) })
    }
}
