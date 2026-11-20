package dev.miubridge.phone

/** A platform-free copy of one accessibility node; [handle] carries the live node for actions. */
data class UiNode(
    val className: String = "",
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val password: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val handle: Any? = null,
)

data class UiWindow(val title: String?, val root: UiNode)

/**
 * What a ref stands for: an element, not a spot on the screen. [handle] is the live node, asked for
 * its position when an action happens; [role], [name], [id] and [nth] (its place among elements with
 * the same three) find it again if that node has been replaced. [x],[y] are where it was when the
 * snapshot was taken, for display only.
 */
data class UiRef(
    val x: Int, val y: Int, val editable: Boolean, val handle: Any?,
    val role: String = "", val name: String? = null, val id: String? = null, val nth: Int = 0, val enabled: Boolean = true,
) {
    fun sameElementAs(other: UiRef) = role == other.role && name == other.name && id == other.id
}

/**
 * A way to name an element that does not depend on a snapshot's numbering:
 * `text=Send` (contains, any case), `text="Send"` (exact), `id=send_btn` (resource id),
 * `role=button`, `role=button[name="Send"]`, `role=button[name*="Sen"]`, each optionally followed by
 * ` >> nth=N` (0-based, -1 for the last) to pick one of several.
 */
class Selector private constructor(val source: String, private val test: (UiRef) -> Boolean, val nth: Int?) {
    /** The refs of [snapshot] this names, in screen order, after [nth] is applied. */
    fun matches(snapshot: UiSnapshot): List<Map.Entry<String, UiRef>> {
        val all = snapshot.refs.entries.filter { test(it.value) }
        val n = nth ?: return all
        return listOfNotNull(all.getOrNull(if (n < 0) all.size + n else n))
    }

    companion object {
        private val REF = Regex("^\\[?e?(\\d+)]?$")
        private val NTH = Regex("\\s*>>\\s*nth=(-?\\d+)\\s*$")
        private val ROLE = Regex("^role=([a-z]+)(?:\\[name(\\*?)=(.*)])?$")

        /** The ref key ("e5") when [target] is a ref rather than a selector. */
        fun refKey(target: String): String? = REF.find(target.trim())?.let { "e${it.groupValues[1]}" }

        /** Parses [target]; throws IllegalArgumentException with what forms are accepted. */
        fun parse(target: String): Selector {
            var body = target.trim()
            val nth = NTH.find(body)?.also { body = body.removeRange(it.range).trim() }?.groupValues?.get(1)?.toInt()
            fun unquote(v: String) = v.trim().let { if (it.length >= 2 && it.first() == it.last() && it.first() in "\"'") it.substring(1, it.length - 1) else it }
            fun quoted(v: String) = v.trim().let { it.length >= 2 && it.first() == it.last() && it.first() in "\"'" }
            val test: (UiRef) -> Boolean = when {
                body.startsWith("text=") -> body.removePrefix("text=").let { v ->
                    val want = unquote(v)
                    require(want.isNotEmpty()) { "text= needs some text" }
                    val byText: (UiRef) -> Boolean =
                        if (quoted(v)) { r -> r.name == want } else { r -> r.name?.contains(want, ignoreCase = true) == true }
                    byText
                }
                body.startsWith("id=") -> unquote(body.removePrefix("id=")).removePrefix("#").let { want ->
                    require(want.isNotEmpty()) { "id= needs a resource id" }
                    val byId: (UiRef) -> Boolean = { r -> r.id == want }
                    byId
                }
                else -> ROLE.find(body)?.let { m ->
                    val role = m.groupValues[1]
                    val partial = m.groupValues[2] == "*"
                    val name = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::unquote)
                    val t: (UiRef) -> Boolean = { r ->
                        r.role == role && (name == null || if (partial) r.name?.contains(name, ignoreCase = true) == true else r.name == name)
                    }
                    t
                } ?: throw IllegalArgumentException(
                    "not a ref or a selector: \"$target\". Use a ref from the latest snapshot (e5), text=…, text=\"exact\", id=…, " +
                        "or role=button[name=\"…\"], optionally followed by >> nth=N",
                )
            }
            return Selector(target.trim(), test, nth)
        }
    }
}

data class UiSnapshot(
    val text: String, val packageName: String?, val refs: Map<String, UiRef>,
    /** An app is open but exposes no element tree: only a picture shows what is on it. */
    val unreadable: Boolean = false,
    /** A progress indicator is showing: the screen is still loading and is about to change. */
    val busy: Boolean = false,
)

/**
 * Renders windows as one line per meaningful node: `[e3] button "OK" @540,1620 #ok_btn (disabled)`.
 * Invisible subtrees and empty containers are skipped. A clickable container without its own text
 * is labelled with its descendants' texts, and those plain text descendants are then not repeated.
 */
object SnapshotFormatter {
    private const val MAX_LABEL = 80

    fun format(
        header: String, packageName: String?, windows: List<UiWindow>, width: Int, height: Int, maxLines: Int = 300,
        unreadable: Boolean = false,
    ): UiSnapshot {
        val lines = mutableListOf(header)
        val refs = LinkedHashMap<String, UiRef>()
        var omitted = 0
        var busy = false

        fun visit(node: UiNode, depth: Int, covered: Boolean) {
            if (!node.visible) return
            val hasArea = node.right > node.left && node.bottom > node.top
            if (hasArea && node.className.endsWith("ProgressBar")) busy = true
            val own = label(node)
            val actionable = node.clickable || node.longClickable || node.checkable
            val interactive = actionable || node.editable || node.scrollable
            val show = hasArea && (interactive || (own != null && !covered))
            var childDepth = depth
            var childCovered = covered
            if (show) {
                if (lines.size - 1 >= maxLines) {
                    omitted++
                } else {
                    val borrowed = if (own == null && actionable) gatherText(node.children) else null
                    val ref = "e${refs.size + 1}"
                    val x = (maxOf(node.left, 0) + minOf(node.right, width)) / 2
                    val y = (maxOf(node.top, 0) + minOf(node.bottom, height)) / 2
                    val name = own ?: borrowed
                    val id = node.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }
                    val draft = UiRef(x, y, node.editable, node.handle, role(node), name, id, 0, node.enabled)
                    refs[ref] = draft.copy(nth = refs.values.count { it.sameElementAs(draft) })
                    lines += line(node, ref, depth, name, x, y)
                    childCovered = covered || borrowed != null
                }
                childDepth = depth + 1
            }
            for (child in node.children) visit(child, childDepth, childCovered)
        }

        windows.forEachIndexed { i, w ->
            if (windows.size > 1) lines += "-- window ${i + 1}" + (w.title?.let { " \"${clean(it)}\"" } ?: "")
            visit(w.root, 0, false)
        }
        if (windows.isEmpty()) {
            lines += if (unreadable) "(this screen exposes no element tree; a screenshot comes with this result, tap by x,y)"
            else "(nothing is open on the background display; use phone_launch)"
        }
        if (omitted > 0) lines += "… $omitted more elements omitted; scroll, or use phone_screenshot"
        return UiSnapshot(lines.joinToString("\n"), packageName, refs, unreadable && windows.isEmpty(), busy)
    }

    private fun line(n: UiNode, ref: String, depth: Int, label: String?, x: Int, y: Int): String = buildString {
        append("  ".repeat(depth)).append('[').append(ref).append("] ").append(role(n))
        if (label != null) append(" \"").append(label).append('"')
        if (n.editable && n.text.isNullOrBlank() && !n.hint.isNullOrBlank()) append(" hint=\"").append(clean(n.hint)).append('"')
        append(" @").append(x).append(',').append(y)
        n.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { append(" #").append(it) }
        val flags = buildList {
            if (n.checkable) add(if (n.checked) "checked" else "unchecked")
            if (n.selected) add("selected")
            if (n.editable && n.focused) add("focused")
            if (n.scrollable) add("scrollable")
            if (n.longClickable && !n.clickable) add("long-press")
            if (!n.enabled) add("disabled")
        }
        if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(')')
    }

    private fun label(n: UiNode): String? {
        if (n.password && !n.text.isNullOrEmpty()) return "••••"
        val t = n.text?.let(::clean)?.takeIf { it.isNotEmpty() && it != n.hint?.let(::clean) }
        return t ?: n.desc?.let(::clean)?.takeIf { it.isNotEmpty() }
    }

    /** Texts of plain (non-interactive) visible descendants, for labelling a clickable container. */
    private fun gatherText(children: List<UiNode>): String? {
        val parts = mutableListOf<String>()
        fun walk(n: UiNode) {
            if (!n.visible || n.clickable || n.longClickable || n.checkable || n.editable) return
            label(n)?.let { if (it !in parts) parts += it }
            n.children.forEach(::walk)
        }
        children.forEach(::walk)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let(::truncate)
    }

    private fun role(n: UiNode): String {
        val c = n.className.substringAfterLast('.')
        return when {
            n.editable || c.endsWith("EditText") -> "input"
            c.endsWith("CheckBox") -> "checkbox"
            c.endsWith("Switch") || c.endsWith("SwitchCompat") || c.endsWith("ToggleButton") -> "switch"
            c.endsWith("RadioButton") -> "radio"
            c.endsWith("Button") -> "button"
            c.contains("WebView") -> "web"
            n.scrollable -> "list"
            n.clickable || n.longClickable -> "button"
            c.endsWith("ImageView") -> "image"
            c.endsWith("TextView") -> "text"
            else -> "view"
        }
    }

    private fun clean(s: CharSequence): String = truncate(s.toString().replace(Regex("\\s+"), " ").replace('"', '\'').trim())

    private fun truncate(s: String) = if (s.length > MAX_LABEL) s.take(MAX_LABEL - 1) + "…" else s
}
