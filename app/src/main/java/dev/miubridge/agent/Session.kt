package dev.miubridge.agent

import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * One session file in pi's format (version 3): a header line, then entries that form a tree through
 * `id`/`parentId`. The leaf is the newest entry on the active branch. Nothing is ever rewritten.
 */
class Session private constructor(val file: File, val id: String, private val entries: MutableList<JSONObject>) {
    private var leafId: String? = entries.lastOrNull()?.optString("id")

    /** Entries on the path from the root to the leaf. */
    fun branch(): List<JSONObject> {
        val byId = entries.associateBy { it.optString("id") }
        val path = ArrayList<JSONObject>()
        var cur = leafId?.let { byId[it] }
        while (cur != null) {
            path += cur
            cur = cur.optString("parentId").takeIf { it.isNotEmpty() && it != "null" }?.let { byId[it] }
        }
        return path.asReversed()
    }

    fun appendMessage(message: JSONObject): JSONObject = append(JSONObject().put("type", "message").put("message", message))

    fun appendCompaction(summary: String, firstKeptEntryId: String, tokensBefore: Int, details: JSONObject): JSONObject =
        append(JSONObject().put("type", "compaction").put("summary", summary).put("firstKeptEntryId", firstKeptEntryId)
            .put("tokensBefore", tokensBefore).put("details", details))

    @Synchronized
    private fun append(entry: JSONObject): JSONObject {
        entry.put("id", newId()).put("parentId", leafId ?: JSONObject.NULL).put("timestamp", iso(Date()))
        file.appendText(entry.toString() + "\n")
        entries += entry
        leafId = entry.getString("id")
        return entry
    }

    private fun newId(): String {
        val ids = entries.mapTo(HashSet()) { it.optString("id") }
        while (true) {
            val b = ByteArray(4).also { RANDOM.nextBytes(it) }
            val id = b.joinToString("") { "%02x".format(it) }
            if (id !in ids) return id
        }
    }

    companion object {
        private val RANDOM = SecureRandom()

        fun iso(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

        fun create(dir: File): Session {
            dir.mkdirs()
            val id = UUID.randomUUID().toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val file = File(dir, "${stamp}_$id.jsonl")
            file.writeText(JSONObject().put("type", "session").put("version", 3).put("id", id)
                .put("timestamp", iso(Date())).put("cwd", "phone").toString() + "\n")
            return Session(file, id, ArrayList())
        }

        fun open(file: File): Session? = runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            val header = JSONObject(lines.first())
            if (header.optString("type") != "session") return null
            Session(file, header.getString("id"), lines.drop(1).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toMutableList())
        }.getOrNull()

        /** The most recently modified session, like `pi --continue`. */
        fun latest(dir: File): Session? = dir.listFiles { f -> f.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }?.firstNotNullOfOrNull { open(it) }

        /**
         * pi's buildSessionContext: the messages sent to the model for a branch. With a compaction on the path,
         * its summary comes first, then the kept messages from firstKeptEntryId, then everything after it.
         */
        fun buildContext(path: List<JSONObject>): List<JSONObject> {
            val compactionIndex = path.indexOfLast { it.optString("type") == "compaction" }
            fun msg(e: JSONObject): JSONObject? = when (e.optString("type")) {
                "message" -> e.optJSONObject("message")
                else -> null
            }
            if (compactionIndex < 0) return path.mapNotNull(::msg)
            val c = path[compactionIndex]
            val out = ArrayList<JSONObject>()
            out += Messages.compactionSummary(c.optString("summary"), c.optInt("tokensBefore"), Date().time)
            val firstKept = path.indexOfFirst { it.optString("id") == c.optString("firstKeptEntryId") }
            if (firstKept in 0 until compactionIndex) for (i in firstKept until compactionIndex) msg(path[i])?.let(out::add)
            for (i in compactionIndex + 1 until path.size) msg(path[i])?.let(out::add)
            return out
        }
    }
}
