package dev.miubridge.server

import dev.miubridge.core.CommandResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TracesTest {
    private val allowed = setOf("t", "tool", "ms", "ok", "sel", "code", "lines", "pkg")

    @Test
    fun anEventCarriesNoScreenTextOrArguments() {
        val secret = "Meet Alice at 5, code 482913"
        val params = JSONObject().put("ref", "text=$secret").put("text", secret)
        val result = CommandResult.Ok(JSONObject()
            .put("snapshot", "display #3 1080x2400\n[e1] text \"$secret\" @1,1\n[e2] button \"Send\" @2,2")
            .put("package", "com.example.chat").put("done", "typed into \"$secret\""))
        val e = Traces.event("phone_type", params, result, 812, 60_000 * 1000L)
        assertTrue(e.keys().asSequence().all { it in allowed })
        assertFalse(e.toString().contains("Alice"))
        assertFalse(e.toString().contains("482913"))
        assertEquals("text", e.getString("sel"))
        assertEquals(2, e.getInt("lines"))
        assertEquals("com.example.chat", e.getString("pkg"))
        assertEquals(1000L, e.getLong("t"))
    }

    @Test
    fun aFailureCarriesItsCodeAndTheLaunchedPackage() {
        val e = Traces.event("phone_launch", JSONObject().put("package", "com.android.settings"),
            CommandResult.Error("no element matches", "not_found"), 40, 0)
        assertEquals(false, e.getBoolean("ok"))
        assertEquals("not_found", e.getString("code"))
        assertEquals("com.android.settings", e.getString("pkg"))
    }

    @Test
    fun howTheElementWasNamed() {
        fun sel(p: JSONObject) = Traces.event("phone_tap", p, CommandResult.Ok(JSONObject()), 1, 0).optString("sel")
        assertEquals("ref", sel(JSONObject().put("ref", "e12")))
        assertEquals("id", sel(JSONObject().put("ref", "id=send_btn")))
        assertEquals("role", sel(JSONObject().put("ref", "role=button[name=\"OK\"]")))
        assertEquals("xy", sel(JSONObject().put("x", 3).put("y", 4)))
        assertEquals("", sel(JSONObject().put("ref", "something else")))
    }
}
