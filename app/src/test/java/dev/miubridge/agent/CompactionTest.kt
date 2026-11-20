package dev.miubridge.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CompactionTest {
    private fun session() = Session.create(Files.createTempDirectory("s").toFile())

    private fun assistantCall(id: String, name: String, args: JSONObject) = JSONObject().put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "toolCall").put("id", id).put("name", name).put("arguments", args)))
        .put("stopReason", "toolUse").put("usage", JSONObject().put("totalTokens", 1000))

    private fun assistantText(t: String) = JSONObject().put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", t))).put("stopReason", "stop")
        .put("usage", JSONObject().put("totalTokens", 2000))

    private fun result(id: String, text: String) = JSONObject().put("role", "toolResult").put("toolCallId", id).put("toolName", "phone_snapshot")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", false)

    /** Ten turns of ~2,000 tokens each: user, a tool call, a big tool result, an answer. */
    private fun longSession(): Session {
        val s = session()
        repeat(10) { i ->
            s.appendMessage(JSONObject().put("role", "user").put("content", "task $i"))
            s.appendMessage(assistantCall("c$i", "read", JSONObject().put("path", "skills/s$i/SKILL.md")))
            s.appendMessage(result("c$i", "x".repeat(8000)))
            s.appendMessage(assistantText("done $i"))
        }
        return s
    }

    @Test fun cutKeepsRecentTokensAndStartsAtAMessageThatIsNotAToolResult() {
        val s = longSession()
        val p = Compaction.prepare(s.branch(), Compaction.Settings(keepRecentTokens = 5000))!!
        val kept = s.branch().first { it.optString("id") == p.firstKeptEntryId }
        assertTrue(kept.getJSONObject("message").optString("role") in setOf("user", "assistant"))
        assertTrue(p.messagesToSummarize.isNotEmpty())
        assertTrue(p.readFiles.contains("skills/s0/SKILL.md"))
    }

    @Test fun contextAfterCompactionIsSummaryThenKeptMessages() {
        val s = longSession()
        val p = Compaction.prepare(s.branch(), Compaction.Settings(keepRecentTokens = 5000))!!
        s.appendCompaction("SUMMARY", p.firstKeptEntryId, p.tokensBefore, JSONObject())
        val ctx = Session.buildContext(s.branch())
        assertEquals("compactionSummary", ctx.first().optString("role"))
        assertEquals("SUMMARY", ctx.first().optString("summary"))
        val firstKept = s.branch().first { it.optString("id") == p.firstKeptEntryId }.getJSONObject("message")
        assertEquals(firstKept.toString(), ctx[1].toString())
        assertTrue(ctx.size < Session.buildContext(s.branch().dropLast(1)).size)
    }

    @Test fun secondCompactionStartsFromPreviousKeptEntryAndCarriesSummary() {
        val s = longSession()
        val p1 = Compaction.prepare(s.branch(), Compaction.Settings(keepRecentTokens = 5000))!!
        s.appendCompaction("FIRST", p1.firstKeptEntryId, p1.tokensBefore, JSONObject().put("readFiles", JSONArray().put("old.md")).put("modifiedFiles", JSONArray()))
        assertEquals(null, Compaction.prepare(s.branch(), Compaction.Settings())) // nothing after the compaction yet
        s.appendMessage(JSONObject().put("role", "user").put("content", "more"))
        val p2 = Compaction.prepare(s.branch(), Compaction.Settings(keepRecentTokens = 10))!!
        assertEquals("FIRST", p2.previousSummary)
        assertTrue(p2.readFiles.contains("old.md"))
    }

    @Test fun reopenedSessionRebuildsTheSameBranch() {
        val s = longSession()
        val again = Session.open(s.file)!!
        assertEquals(s.branch().map { it.optString("id") }, again.branch().map { it.optString("id") })
        assertFalse(again.branch().first().has("parentId") && again.branch().first().get("parentId") != JSONObject.NULL)
    }

    @Test fun orphanedToolCallGetsAPlaceholderResult() {
        val msgs = listOf(
            JSONObject().put("role", "user").put("content", "go"),
            assistantCall("a", "phone_tap", JSONObject()),
            JSONObject().put("role", "user").put("content", "stop and do something else"),
        )
        val out = Messages.toLlm("sys", msgs)
        assertEquals("tool", out.getJSONObject(3).optString("role"))
        assertEquals("a", out.getJSONObject(3).optString("tool_call_id"))
        assertEquals("user", out.getJSONObject(4).optString("role"))
    }

    @Test fun serializationTruncatesToolResults() {
        val text = Compaction.serialize(listOf(result("a", "y".repeat(5000))))
        assertTrue(text.startsWith("[Tool result]: "))
        assertTrue(text.contains("[... 3000 more characters truncated]"))
    }
}
