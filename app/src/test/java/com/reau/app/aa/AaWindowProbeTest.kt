package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug probe's lines: the side bar's texts are written, those of every other window are not. */
class AaWindowProbeTest {
    private fun node(cls: String = "android.widget.ImageButton", id: String? = null, desc: String? = null, text: String? = null, selected: Boolean = false) =
        AaWindowProbe.Node(cls, id, desc, text, selected, checked = false, focused = false)

    @Test
    fun `the side bar says which app is selected`() {
        val line = AaWindowProbe.line(
            "GhFacetBar", "Barra", AaWindowProbe.State(),
            nodes = listOf(node(desc = "Waze", selected = true), node(desc = "Spotify"), node(cls = "android.view.View")),
        )
        assertTrue(line, "ImageButton desc='Waze' selected" in line)
        assertTrue(line, "ImageButton desc='Spotify'" in line)
        assertTrue("a view with nothing to say is counted, not listed: $line", "views=3" in line && line.count { it == '|' } == 1)
    }

    @Test
    fun `a window that is not the side bar never has its texts written`() {
        val line = AaWindowProbe.line(
            "TemplateService", "Spotify", AaWindowProbe.State(focused = true),
            nodes = listOf(node(cls = "android.widget.TextView", id = "com.x:id/title", text = "Rua das Flores", desc = "Rua das Flores")),
        )
        assertFalse(line, "Rua" in line)
        assertTrue(line, "TextView#title desc(14) text(14)" in line)
        assertTrue(line, line.startsWith("probe: TemplateService focused layer=0 title='Spotify'"))
    }

    @Test
    fun `long texts are cut and quotes cannot break the line`() {
        val line = AaWindowProbe.line("GhFacetBar", null, AaWindowProbe.State(), listOf(node(desc = "a'b" + "x".repeat(100))))
        assertTrue(line, "desc='a’bxxx" in line)
        assertTrue(line, line.substringAfter("desc='").substringBefore('\'').length <= AaWindowProbe.MAX_TEXT)
    }

    @Test
    fun `at most the first views are listed and the rest counted`() {
        val line = AaWindowProbe.line("GhFacetBar", "", AaWindowProbe.State(), List(AaWindowProbe.MAX_NODES + 5) { node(desc = "n$it") })
        assertTrue(line, line.endsWith("(+5 more)"))
    }

    @Test
    fun `a change is written once and not within the gap`() {
        val d = AaWindowProbe.Dedupe()
        assertEquals("a", d.offer(10_000, listOf("a")))
        assertNull("same", d.offer(20_000, listOf("a")))
        assertNull("changed but too soon: kept for the next read", d.offer(10_500, listOf("b")))
        assertEquals("b", d.offer(11_000, listOf("b")))
    }

    @Test
    fun `no window of Android Auto writes nothing but the one coming back is a change`() {
        val d = AaWindowProbe.Dedupe()
        assertEquals("a", d.offer(10_000, listOf("a")))
        assertNull("no window: no empty line", d.offer(20_000, emptyList()))
        assertEquals("a", d.offer(30_000, listOf("a")))
    }

    @Test
    fun `the line says which window is active and its layer`() {
        val line = AaWindowProbe.line("2 TemplateService", "Android Auto", AaWindowProbe.State(focused = true, active = true, layer = 3), nodes = emptyList())
        assertTrue(line, line.startsWith("probe: 2 TemplateService focused active layer=3 title='Android Auto'"))
        val app = AaWindowProbe.line("2 TemplateService", "", AaWindowProbe.State(pkg = "com.example.app"), nodes = emptyList())
        assertTrue("the window's app is said: $app", " pkg=com.example.app title=" in app)
    }
}
