package dev.miubridge.agent

import android.content.Context
import dev.miubridge.core.CommandSpec
import dev.miubridge.core.Param
import java.io.File

/**
 * The agent's working directory, as pi sees a project: `AGENTS.md` is loaded into the system prompt, and
 * `skills/<name>/SKILL.md` (with `name` and `description` front matter) are listed and read on demand.
 * The read, write and edit tools only reach files in here.
 */
class Workspace(context: Context, private val skillStore: dev.miubridge.core.Skills) {
    val root: File = File(context.filesDir, "workspace")

    data class Skill(val name: String, val description: String, val path: String)

    init {
        if (!File(root, "AGENTS.md").exists()) seed()
    }

    fun agentsMd(): String? = File(root, "AGENTS.md").takeIf { it.isFile }?.readText()

    fun skills(): List<Skill> = skillStore.list().map { Skill(it.name, it.description, it.path) }

    val specs: Map<String, CommandSpec> = mapOf(
        "read" to CommandSpec(
            "Read a text file in the workspace. Use offset/limit (1-based lines) for long files.",
            required = mapOf("path" to Param("string", "Path relative to the workspace, e.g. skills/wechat/SKILL.md")),
            optional = mapOf("offset" to Param("integer", "First line to read, 1-based."), "limit" to Param("integer", "Maximum number of lines.")),
        ),
        "write" to CommandSpec(
            "Create or overwrite a file in the workspace with the given content.",
            required = mapOf("path" to Param("string", "Path relative to the workspace."), "content" to Param("string", "The whole file content.")),
        ),
        "bash" to CommandSpec(
            "Run a shell command on the phone, in the workspace directory. This is Android's sh (mksh with toybox tools: " +
                "ls, cat, grep, sed, find, sort, wc, date...), not GNU bash, and there is no python or node. It runs in the app's " +
                "own sandbox: it can read and write only the workspace and cannot touch other apps (use the phone tools for those). " +
                "Output is the combined stdout and stderr, cut at 20,000 characters. Limit 60 seconds.",
            required = mapOf("command" to Param("string", "The command line, as for sh -c.")),
            timeoutMs = 65_000,
        ),
        "edit" to CommandSpec(
            "Replace exact text in a workspace file. oldText must occur exactly once.",
            required = mapOf(
                "path" to Param("string", "Path relative to the workspace."),
                "oldText" to Param("string", "Exact text to find."),
                "newText" to Param("string", "Replacement text."),
            ),
        ),
    )

    fun read(path: String, offset: Int?, limit: Int?): String {
        val f = file(path)
        require(f.isFile) { "no such file: $path" }
        // A skill's manual comes with what agents learned (LEARNED.md) appended.
        val m = Regex("^skills/([a-z0-9-]+)/SKILL\\.md$").find(path.removePrefix("./"))
        val lines = (if (m != null) skillStore.read(m.groupValues[1]) else f.readText()).lines()
        val from = ((offset ?: 1) - 1).coerceIn(0, lines.size)
        val to = if (limit != null) (from + limit).coerceAtMost(lines.size) else lines.size
        return lines.subList(from, to).joinToString("\n")
    }

    /** Runs [command] with sh as this app's own user, in the workspace. The app sandbox is the sandbox. */
    fun bash(command: String, timeoutMs: Long = 60_000): String {
        root.mkdirs()
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(root)
            .redirectErrorStream(true)
            .apply { environment().apply { put("HOME", root.path); put("TMPDIR", root.path) } }
            .start()
        val out = StringBuilder()
        val reader = kotlin.concurrent.thread {
            runCatching { process.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) { val n = r.read(buf); if (n < 0) break; if (out.length < 20_000) synchronized(out) { out.append(buf, 0, n) } }
            } }
        }
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1_000)
        val text = synchronized(out) { out.toString().take(20_000) }
        return if (finished) "$text\n[exit ${process.exitValue()}]".trim() else "$text\n[killed after ${timeoutMs / 1000} s]".trim()
    }

    fun write(path: String, content: String): String {
        require(content.length <= MAX_FILE) { "file too large (max $MAX_FILE characters)" }
        val f = file(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "Wrote ${content.length} characters to $path"
    }

    fun edit(path: String, oldText: String, newText: String): String {
        val f = file(path)
        require(f.isFile) { "no such file: $path" }
        val text = f.readText()
        val first = text.indexOf(oldText)
        require(oldText.isNotEmpty() && first >= 0) { "oldText not found in $path" }
        require(text.indexOf(oldText, first + 1) < 0) { "oldText occurs more than once in $path; include more context" }
        f.writeText(text.replaceRange(first, first + oldText.length, newText))
        return "Edited $path"
    }

    private fun file(path: String): File {
        val f = File(root, path.removePrefix("./")).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "path must stay inside the workspace" }
        return f
    }


    private fun seed() {
        write("AGENTS.md", AGENTS)
    }

    private companion object {
        const val MAX_FILE = 20_000

        val AGENTS = """
            # Owner's phone

            - This is the owner's own Huawei phone (HarmonyOS 4.2, Android 12). Reply in Chinese.
            - Before sending, posting, replying, paying, deleting, or anything that cannot be undone: stop, tell the owner
              exactly what you are about to do (quote the text), and wait for their reply. Continue only on a clear yes.
            - Login, verification codes, face checks and payments are the owner's: use phone_handoff and say what is waiting.
            - Keep what you read in apps private: report only what the task needs.
        """.trimIndent() + "\n"

    }
}
