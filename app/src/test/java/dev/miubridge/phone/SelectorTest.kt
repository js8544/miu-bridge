package dev.miubridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorTest {
    private fun node(cls: String, text: String? = null, id: String? = null, clickable: Boolean = true, top: Int = 0) =
        UiNode(className = cls, text = text, viewId = id?.let { "com.app:id/$it" }, left = 0, top = top, right = 100, bottom = top + 50, clickable = clickable)

    private val snapshot = SnapshotFormatter.format(
        "display", "com.app",
        listOf(UiWindow(null, UiNode(className = "android.widget.FrameLayout", right = 100, bottom = 500, children = listOf(
            node("android.widget.Button", "Send", "send_btn"),
            node("android.widget.Button", "Add", "add", top = 60),
            node("android.widget.Button", "Add", "add", top = 120),
            node("android.widget.TextView", "Send later", clickable = false, top = 180),
            node("android.widget.EditText", "hello", "input", top = 240),
        )))),
        100, 500,
    )

    private fun keys(selector: String) = Selector.parse(selector).matches(snapshot).map { it.key }

    @Test fun refsAreRecognised() {
        assertEquals("e5", Selector.refKey("e5"))
        assertEquals("e5", Selector.refKey("[e5]"))
        assertEquals("e5", Selector.refKey("5"))
        assertNull(Selector.refKey("text=5"))
    }

    @Test fun textContainsAndExact() {
        assertEquals(listOf("e1", "e4"), keys("text=send"))
        assertEquals(listOf("e1"), keys("text=\"Send\""))
        assertTrue(keys("text=\"send\"").isEmpty())
    }

    @Test fun idAndRole() {
        assertEquals(listOf("e1"), keys("id=send_btn"))
        assertEquals(listOf("e2", "e3"), keys("role=button[name=\"Add\"]"))
        assertEquals(listOf("e1"), keys("role=button[name*=\"sen\"]"))
        assertEquals(listOf("e5"), keys("role=input"))
    }

    @Test fun nthPicksOne() {
        assertEquals(listOf("e3"), keys("id=add >> nth=1"))
        assertEquals(listOf("e3"), keys("role=button[name=\"Add\"] >> nth=-1"))
        assertTrue(keys("id=add >> nth=5").isEmpty())
    }

    @Test fun alikeElementsAreToldApartOnlyByPosition() {
        val (a, b) = snapshot.refs.getValue("e2") to snapshot.refs.getValue("e3")
        assertTrue(a.sameElementAs(b))
        assertTrue(a.x != b.x || a.y != b.y)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownForms() { Selector.parse("css=.btn") }
}
