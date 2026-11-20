package dev.miubridge.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFormatterTest {
    private fun box(l: Int, t: Int, r: Int, b: Int) = UiNode(left = l, top = t, right = r, bottom = b)

    private val tree = box(0, 0, 1080, 2340).copy(
        className = "android.widget.FrameLayout",
        children = listOf(
            box(0, 100, 1080, 300).copy(
                className = "android.widget.LinearLayout",
                clickable = true,
                viewId = "com.android.settings:id/wifi_row",
                children = listOf(
                    box(40, 120, 600, 180).copy(className = "android.widget.TextView", text = "Wi-Fi"),
                    box(40, 200, 600, 260).copy(className = "android.widget.TextView", text = "已连接\nHome"),
                    box(900, 150, 1040, 250).copy(className = "android.widget.Switch", checkable = true, checked = true, clickable = true),
                ),
            ),
            box(0, 400, 1080, 500).copy(className = "android.widget.EditText", editable = true, hint = "搜索", focused = true),
            box(0, 600, 1080, 700).copy(className = "android.widget.TextView", text = "secret", visible = false),
            box(0, 800, 1080, 2340).copy(
                className = "androidx.recyclerview.widget.RecyclerView",
                scrollable = true,
                children = listOf(box(0, 800, 1080, 900).copy(className = "android.widget.TextView", text = "Bluetooth")),
            ),
            box(0, 0, 0, 0).copy(className = "android.view.View", text = "zero-size"),
            box(0, 2200, 1080, 2400).copy(className = "android.widget.Button", text = "确定", enabled = false),
        ),
    )

    @Test
    fun formatsMeaningfulNodes() {
        val snap = SnapshotFormatter.format("display #2 1080x2340", "com.android.settings", listOf(UiWindow("Settings", tree)), 1080, 2340)
        val expected = """
            display #2 1080x2340
            [e1] button "Wi-Fi · 已连接 Home" @540,200 #wifi_row
              [e2] switch @970,200 (checked)
            [e3] input hint="搜索" @540,450 (focused)
            [e4] list @540,1570 (scrollable)
              [e5] text "Bluetooth" @540,850
            [e6] button "确定" @540,2270 (disabled)
        """.trimIndent()
        assertEquals(expected, snap.text)
        assertEquals(6, snap.refs.size)
        assertEquals(UiRef(540, 450, true, null, role = "input"), snap.refs["e3"])
        // the button is clipped to the display: (2200 + 2340) / 2
        assertEquals(2270, snap.refs["e6"]!!.y)
        assertFalse(snap.text.contains("secret"))
        assertFalse(snap.text.contains("zero-size"))
    }

    @Test
    fun masksPasswordsAndCapsLines() {
        val many = box(0, 0, 100, 100).copy(
            children = List(10) { box(0, it * 10, 100, it * 10 + 10).copy(text = "row $it") } +
                box(0, 0, 100, 10).copy(editable = true, password = true, text = "hunter2"),
        )
        val snap = SnapshotFormatter.format("h", null, listOf(UiWindow(null, many)), 100, 100, maxLines = 3)
        assertEquals(4, snap.text.lines().size - 1) // header + 3 lines + omitted note
        assertTrue(snap.text.endsWith("… 8 more elements omitted; scroll, or use phone_screenshot"))
        val all = SnapshotFormatter.format("h", null, listOf(UiWindow(null, many)), 100, 100)
        assertTrue(all.text.contains("[e11] input \"••••\""))
        assertFalse(all.text.contains("hunter2"))
    }

    @Test
    fun emptyDisplaySaysSo() {
        val snap = SnapshotFormatter.format("h", null, emptyList(), 100, 100)
        assertTrue(snap.text.contains("phone_launch"))
    }
}
