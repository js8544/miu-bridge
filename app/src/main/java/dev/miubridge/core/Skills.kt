package dev.miubridge.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App manuals shared by every agent: the on-phone agent reads them with its read tool, MCP clients through
 * phone_skill_read. Nothing ships with the app: `skills/<name>/SKILL.md` is a header an agent created, and
 * `skills/<name>/LEARNED.md` holds what agents learned about that app on this phone: dated facts that updates
 * never touch, that agents can correct or remove, and whose oldest entries give way when the list grows too long.
 */
class Skills(private val context: Context, val root: File) {
    data class Skill(val name: String, val description: String, val packages: List<String>, val path: String)

    init {
        syncBundled()
    }

    fun list(): List<Skill> = root.listFiles()?.filter { File(it, SKILL).isFile }?.map { dir ->
        val meta = frontMatter(File(dir, SKILL).readText())
        val pkgs = meta["packages"]?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
        Skill(meta["name"] ?: dir.name, meta["description"].orEmpty(), pkgs, "skills/${dir.name}/$SKILL")
    }?.sortedBy { it.name }.orEmpty()

    /** The manual with what has been learned appended, or a file under its references/. */
    fun read(name: String, file: String? = null): String {
        val dir = dir(name)
        require(dir.isDirectory) { "no skill named $name; phone_skills lists them" }
        if (file != null) {
            val f = File(dir, file).canonicalFile
            require(f.path.startsWith(dir.canonicalPath + File.separator) && f.isFile) { "no file $file in skill $name" }
            return f.readText()
        }
        val learned = File(dir, LEARNED).takeIf { it.isFile }?.readText()?.trim()
        return File(dir, SKILL).readText().trimEnd() + if (learned.isNullOrEmpty()) "" else "\n\n## 学到的补充\n$learned\n"
    }

    /**
     * Records one fact. A new skill needs a description (what the app is, with its package).
     * [replaces] names an existing fact (any distinctive part of its text) that this one corrects;
     * that line is removed. When the list is full the oldest facts make room.
     */
    @Synchronized
    fun learn(name: String, fact: String, description: String? = null, replaces: String? = null, packageName: String? = null): String {
        require(Regex("[a-z0-9][a-z0-9-]{0,60}").matches(name)) { "skill names are lowercase letters, digits and hyphens" }
        val f = fact.trim().removePrefix("- ").replace("\n", " ")
        require(f.length in 4..MAX_FACT) { "a fact is one line of 4 to $MAX_FACT characters" }
        val dir = dir(name)
        if (!File(dir, SKILL).isFile) {
            require(!description.isNullOrBlank()) { "skill $name does not exist yet; pass a description (app name and package) to create it" }
            dir.mkdirs()
            val packages = packageName?.let { "\npackages: [\"$it\"]" }.orEmpty()
            File(dir, SKILL).writeText("---\nname: $name\ndescription: ${description.trim().replace("\n", " ")}$packages\n---\n")
        }
        val learned = File(dir, LEARNED)
        var lines = facts(learned)
        var note = "learned for $name"
        if (!replaces.isNullOrBlank()) {
            val hits = lines.filter { replaces.trim() in it }
            require(hits.size == 1) { if (hits.isEmpty()) "no learned fact in $name contains \"$replaces\"" else "\"$replaces\" matches ${hits.size} facts; quote more of the one you mean" }
            lines = lines - hits.single()
            note = "corrected for $name"
        }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        lines = lines + "- $f（$day）"
        // Facts are in the order they were learned: the oldest go first when there are too many.
        while (lines.size > MAX_FACTS || lines.sumOf { it.length + 1 } > MAX_LEARNED) lines = lines.drop(1)
        learned.writeText(lines.joinToString("\n", postfix = "\n"))
        return note
    }

    /** Removes one learned fact that turned out wrong or out of date, named by a distinctive part of its text. */
    @Synchronized
    fun forget(name: String, fact: String): String {
        val learned = File(dir(name), LEARNED)
        val lines = facts(learned)
        val hits = lines.filter { fact.trim().isNotEmpty() && fact.trim() in it }
        require(hits.size == 1) { if (hits.isEmpty()) "no learned fact in $name contains \"$fact\"" else "\"$fact\" matches ${hits.size} facts; quote more of the one you mean" }
        learned.writeText((lines - hits.single()).joinToString("\n", postfix = "\n"))
        return "forgot for $name: ${hits.single().removePrefix("- ")}"
    }

    /** Records a fact about the app [pkg], under its existing skill or a new one named after the package. */
    fun learnForPackage(pkg: String, label: String, fact: String) {
        val name = list().firstOrNull { pkg in it.packages }?.name
            ?: pkg.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        runCatching {
            if (facts(File(dir(name), LEARNED)).none { fact.take(24) in it }) {
                learn(name, fact, description = "$label（$pkg）", packageName = pkg)
            }
        }
    }

    private fun facts(file: File): List<String> =
        file.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.startsWith("- ") }.orEmpty()

    /** One line per skill for an agent's instructions. */
    fun index(): String = list().joinToString("\n") { "- ${it.name}: ${it.description}" }

    private fun dir(name: String) = File(root, name)

    /**
     * No manuals ship with the app: everything here is what agents learned on this phone. Earlier
     * versions did ship some. Once per app version this removes them and keeps what was found out
     * here: notes measured on this phone that sat inside a manual move to the learned facts, a skill
     * left with nothing is removed, and one with facts keeps them under a bare header.
     */
    private fun syncBundled() {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val stamp = File(root, ".bundled")
        if (stamp.isFile && stamp.readText() == version.toString()) return
        root.mkdirs()
        File(root, "NOTICE.md").delete()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val manual = File(dir, SKILL)
            val text = manual.takeIf { it.isFile }?.readText() ?: return@forEach
            val shipped = FORMER_MARK in text || (dir.name in FORMER_OWN && body(text).isNotBlank())
            if (!shipped) return@forEach // made by an agent on this phone
            val measured = text.substringAfter(MEASURED_HEADING, "").lines().drop(1)
                .takeWhile { !it.startsWith("## ") }.map { it.trim() }.filter { it.startsWith("- ") }
            if (measured.isNotEmpty()) {
                val learned = File(dir, LEARNED)
                val known = facts(learned)
                learned.writeText((known + measured.filter { it !in known }).joinToString("\n", postfix = "\n"))
            }
            File(dir, "references").deleteRecursively()
            if (File(dir, LEARNED).isFile) {
                val meta = frontMatter(text)
                val packages = meta["packages"]?.let { "\npackages: $it" }.orEmpty()
                manual.writeText("---\nname: ${meta["name"] ?: dir.name}\ndescription: ${meta["description"].orEmpty()}$packages\n---\n")
            } else dir.deleteRecursively()
        }
        stamp.writeText(version.toString())
    }

    private fun body(text: String): String =
        if (!text.startsWith("---")) text else text.indexOf("\n---", 3).takeIf { it > 0 }?.let { text.substring(it + 4) }.orEmpty()

    private fun frontMatter(text: String): Map<String, String> {
        if (!text.startsWith("---")) return emptyMap()
        val end = text.indexOf("\n---", 3).takeIf { it > 0 } ?: return emptyMap()
        return text.substring(3, end).lines().mapNotNull { l ->
            val i = l.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()
    }

    private companion object {
        const val SKILL = "SKILL.md"
        const val LEARNED = "LEARNED.md"
        const val MAX_FACT = 300
        const val MAX_FACTS = 120
        const val MAX_LEARNED = 20_000
        /** The front-matter line every formerly bundled app manual carried. */
        const val FORMER_MARK = "source: Adapted from"
        const val MEASURED_HEADING = "## 本机实测"
        /** Manuals of our own that one version shipped. */
        val FORMER_OWN = setOf("phone-background-display", "wechat", "xiaohongshu")
    }
}
