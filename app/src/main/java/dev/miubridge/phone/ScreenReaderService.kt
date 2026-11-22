package dev.miubridge.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the background display's windows only. Events are reduced to "something on the background
 * display changed at time T" (by window id); no event or node content from other displays is read.
 */
class ScreenReaderService : AccessibilityService() {
    /** Uptime of the last event from a window on the target display. */
    @Volatile var lastEventAt = 0L
        private set

    private var targetWindowIds: Set<Int> = emptySet()
    private var idsRefreshedAt = 0L

    override fun onServiceConnected() {
        connected.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected.compareAndSet(this, null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val display = targetDisplay
        if (event == null || display <= 0) return
        val now = SystemClock.uptimeMillis()
        val windowId = event.windowId
        val stale = windowId !in targetWindowIds && now - idsRefreshedAt > 200
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || stale) {
            val ids = windowsOn(display).map { it.id }.toSet()
            idsRefreshedAt = now
            if (ids != targetWindowIds) {
                targetWindowIds = ids
                lastEventAt = now
            }
        }
        if (windowId in targetWindowIds) lastEventAt = now
    }

    /**
     * Replaces the text of the field that is taking input in [pkg], the way a keyboard would: Android 13
     * lets an accessibility service write into the editor that has input focus. It needs neither a
     * keyboard on screen nor the clipboard (which Android closes to every app while the phone is locked)
     * nor the field in the element tree (which some apps keep empty). False when no field of [pkg] is
     * taking input, or before Android 13.
     */
    fun typeAsKeyboard(pkg: String, text: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val method = inputMethod ?: return false
        if (!method.currentInputStarted || method.currentInputEditorInfo?.packageName != pkg) return false
        val editor = method.currentInputConnection ?: return false
        val around = editor.getSurroundingText(MAX_FIELD_CHARS, MAX_FIELD_CHARS, 0)
        if (around != null) {
            val before = around.selectionStart.coerceAtLeast(0)
            editor.deleteSurroundingText(before, (around.text.length - around.selectionEnd).coerceAtLeast(0))
        }
        editor.commitText(text, 1, null)
        return true
    }

    /** Text snapshot of [display]'s windows, topmost window first. */
    fun snapshot(display: Int, width: Int, height: Int): UiSnapshot {
        val windows = windowsOn(display).sortedByDescending { it.layer }
        var budget = MAX_NODES
        fun copy(info: AccessibilityNodeInfo, depth: Int): UiNode {
            budget--
            val r = Rect().also(info::getBoundsInScreen)
            val children = if (depth >= MAX_DEPTH) emptyList() else (0 until info.childCount).mapNotNull { i ->
                if (budget <= 0) null else info.getChild(i)?.let { copy(it, depth + 1) }
            }
            return UiNode(
                className = info.className?.toString().orEmpty(),
                text = info.text?.toString(),
                desc = info.contentDescription?.toString(),
                hint = info.hintText?.toString(),
                viewId = info.viewIdResourceName,
                left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                clickable = info.isClickable,
                longClickable = info.isLongClickable,
                editable = info.isEditable,
                scrollable = info.isScrollable,
                checkable = info.isCheckable,
                checked = info.isChecked,
                selected = info.isSelected,
                focused = info.isFocused,
                enabled = info.isEnabled,
                visible = info.isVisibleToUser,
                password = info.isPassword,
                children = children,
                handle = info,
            )
        }
        val trees = windows.mapNotNull { w -> w.root?.let { UiWindow(w.title?.toString(), copy(it, 0)) } }
        val pkg = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()
        val header = "display #$display ${width}x$height" + (pkg?.let { " · app $it" } ?: "")
        // A window with no readable root is an open app whose tree is hidden, not an empty display.
        return SnapshotFormatter.format(header, pkg, trees, width, height, unreadable = trees.isEmpty() && windows.isNotEmpty())
    }

    /** The app in front on [display], without reading its tree. */
    fun topPackage(display: Int): String? = windowsOn(display).sortedByDescending { it.layer }
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()

    fun hasWindows(display: Int): Boolean = windowsOn(display).isNotEmpty()

    /** The focused editable field on [display], if any. */
    fun focusedInput(display: Int): AccessibilityNodeInfo? =
        windowsOn(display).firstNotNullOfOrNull { w -> w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } }

    /** The windows on [display], without the keyboard: it is not part of any app, and text goes in through phone_type. */
    private fun windowsOn(display: Int): List<AccessibilityWindowInfo> =
        if (display <= 0) emptyList()
        else windowsOnAllDisplays.get(display).orEmpty().filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    companion object {
        private const val MAX_NODES = 1500
        private const val MAX_FIELD_CHARS = 100_000
        private const val MAX_DEPTH = 60

        internal val connected = MutableStateFlow<ScreenReaderService?>(null)
        val current: StateFlow<ScreenReaderService?> = connected

        /** The background display to watch; -1 when none. Display 0 is never read. */
        @Volatile var targetDisplay = -1

        /** Replaces the text of [node] (or its first editable descendant). */
        fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
            node.refresh()
            val target = if (node.isEditable) node else firstEditable(node) ?: return false
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = ArrayDeque(listOf(root))
            var seen = 0
            while (queue.isNotEmpty() && seen++ < 200) {
                val n = queue.removeFirst()
                if (n.isEditable) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
            }
            return null
        }
    }
}
