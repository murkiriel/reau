package com.reau.app.aa

/**
 * What the debug build writes to the ride log to find out whether Android Auto's windows say which app is in front. Nothing in the
 * protocol does (the dash only gets the video, the focus, the navigation and the music state), and the displays' own names only give the
 * host service (`TemplateNavigationService`, `TemplateService`, …), not the app. This looks at the windows of Android Auto (title, and the
 * views by class and id) and, for the side bar with the app icons (GhFacetBar), their descriptions and which one is selected.
 *
 * Texts of the other windows (street names, songs) are never written, only whether a view has one and how long it is. Pure, so the JVM
 * tests cover the lines; the accessibility service reads the nodes.
 */
object AaWindowProbe {
    /** One view of a window, as read. */
    data class Node(
        val cls: String,
        val id: String?,
        val desc: String?,
        val text: String?,
        val selected: Boolean,
        val checked: Boolean,
        val focused: Boolean,
    )

    const val MAX_NODES = 40
    const val MAX_TEXT = 40
    const val MIN_GAP_MS = 1_000L

    /** The side bar of Android Auto, whose views name the apps. */
    fun isSideBar(displayOrWindowName: String): Boolean = "FacetBar" in displayOrWindowName

    /** Whether a view says anything worth a line: a description, a text, an id, or a state. */
    private fun informative(n: Node) = n.desc != null || n.text != null || n.id != null || n.selected || n.checked || n.focused

    private fun clip(s: String) = s.replace('\n', ' ').replace('\'', '’').take(MAX_TEXT)

    /** One view for the line: in the side bar with its texts, elsewhere with their lengths only. */
    internal fun node(n: Node, withText: Boolean): String = buildString {
        append(n.cls.substringAfterLast('.'))
        n.id?.let { append('#').append(it.substringAfterLast('/')) }
        if (withText) {
            n.desc?.let { append(" desc='").append(clip(it)).append('\'') }
            n.text?.let { append(" text='").append(clip(it)).append('\'') }
        } else {
            n.desc?.let { append(" desc(").append(it.length).append(')') }
            n.text?.let { append(" text(").append(it.length).append(')') }
        }
        if (n.selected) append(" selected")
        if (n.checked) append(" checked")
        if (n.focused) append(" focused")
    }

    /** What the system says of a window besides its views: whether it has the focus, whether it is the active one, its layer, its app's package. */
    data class State(val focused: Boolean = false, val active: Boolean = false, val layer: Int = 0, val pkg: String = "")

    /** The line for one window of Android Auto. */
    fun line(display: String, title: String?, state: State, nodes: List<Node>, withText: Boolean = isSideBar(display)): String {
        val shown = nodes.filter(::informative)
        val views = shown.take(MAX_NODES).joinToString(" | ") { node(it, withText) }
        val more = if (shown.size > MAX_NODES) " (+${shown.size - MAX_NODES} more)" else ""
        val flags = (if (state.focused) " focused" else "") + (if (state.active) " active" else "")
        val pkg = if (state.pkg.isEmpty()) "" else " pkg=${state.pkg}"
        return "probe: $display$flags layer=${state.layer}$pkg title='${title?.let(::clip).orEmpty()}' views=${nodes.size} [$views]$more"
    }

    /** Writes the lines only when they changed, and not more often than [MIN_GAP_MS]; a change inside the gap is kept for the next read. */
    class Dedupe {
        private var last = ""
        private var lastMs = Long.MIN_VALUE / 2

        fun offer(nowMs: Long, lines: List<String>): String? {
            val all = lines.joinToString("\n")
            if (all == last || nowMs - lastMs < MIN_GAP_MS) return null
            last = all
            lastMs = nowMs
            // No window of Android Auto at all: nothing to write, but a window coming back is a change
            return all.ifEmpty { null }
        }
    }
}
