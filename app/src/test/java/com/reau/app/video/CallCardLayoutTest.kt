package com.reau.app.video

import com.reau.app.dash.CallRole
import com.reau.app.dash.CallSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Where the call card goes on the dash's 526×300 frame and how its words are measured ([CallCardLayout]; the drawing itself is a bitmap that the unit tests cannot
 * make): at the top, centred, whole on the round glass with room to spare, over the volume card and the legend, and the name's fit. [OverlayRules] says which one is drawn.
 */
class CallCardLayoutTest {
    @Test
    fun `the pill is 236 by 56, centred across and 28 px from the top`() {
        assertEquals(236, CallCardLayout.WIDTH)
        assertEquals(56, CallCardLayout.HEIGHT)
        assertEquals(145, CallCardLayout.X)
        assertEquals(DashGlass.FRAME_W / 2 - 118, CallCardLayout.X)
        assertEquals(28, CallCardLayout.Y)
        assertEquals(84, CallCardLayout.Y + CallCardLayout.HEIGHT)
        assertEquals("centred", DashGlass.FRAME_W - (CallCardLayout.X + CallCardLayout.WIDTH), CallCardLayout.X)
    }

    @Test
    fun `the four rounded corners are at least 4 px inside the glass`() {
        val r = CallCardLayout.RADIUS.toDouble()
        val reach = r * cos(Math.PI / 4)
        val left = CallCardLayout.X.toDouble()
        val right = left + CallCardLayout.WIDTH
        val top = CallCardLayout.Y.toDouble()
        val bottom = top + CallCardLayout.HEIGHT
        val points = listOf(
            left + r - reach to top + r - reach,
            right - r + reach to top + r - reach,
            left + r - reach to bottom - r + reach,
            right - r + reach to bottom - r + reach,
        )
        for ((x, y) in points) {
            assertTrue("($x, $y) is on the glass", DashGlass.isVisible(x, y))
            val dx = x - DashGlass.CENTER_X
            val dy = y - DashGlass.CENTER_Y
            val room = DashGlass.RADIUS - sqrt(dx * dx + dy * dy)
            assertTrue("($x, $y) has only $room px to the edge of the glass", room >= 4.0)
        }
        assertEquals("all of the pill shows", 1.0, DashGlass.visibleFraction(RectPx(CallCardLayout.X, CallCardLayout.Y, CallCardLayout.WIDTH, CallCardLayout.HEIGHT)), 0.0001)
    }

    @Test
    fun `everything in the pill is inside it, the handset left of the words and the name under the small line`() {
        assertEquals(12, CallCardLayout.ICON_X)
        assertEquals(16, CallCardLayout.ICON_Y)
        assertEquals(24, CallCardLayout.ICON)
        assertTrue(CallCardLayout.ICON_X + CallCardLayout.ICON <= CallCardLayout.TEXT_X)
        assertTrue(CallCardLayout.ICON_Y + CallCardLayout.ICON <= CallCardLayout.HEIGHT)
        assertEquals(46, CallCardLayout.TEXT_X)
        assertEquals("the words end 224 px from the left edge, 12 px from the right", 224, CallCardLayout.TEXT_X + CallCardLayout.TEXT_MAX_W)
        assertEquals(178, CallCardLayout.TEXT_MAX_W)
        assertEquals(21, CallCardLayout.HINT_BASELINE)
        assertEquals(46, CallCardLayout.NAME_BASELINE)
        assertTrue("the name's baseline is under the small line's", CallCardLayout.NAME_BASELINE > CallCardLayout.HINT_BASELINE + CallCardLayout.NAME_LETTERS_MIN * 0.5f)
        assertTrue("and inside the pill", CallCardLayout.NAME_BASELINE < CallCardLayout.HEIGHT)
        assertEquals(14f, CallCardLayout.HINT_LETTERS, 0f)
        assertEquals(23f, CallCardLayout.NAME_LETTERS, 0f)
        assertEquals(19f, CallCardLayout.NAME_LETTERS_MIN, 0f)
    }

    @Test
    fun `the call card is over the volume card's place, which wins while it shows, and over the legend, which is not drawn on a call`() {
        val volumeBottom = VolumeCardLayout.Y + VolumeCardLayout.HEIGHT
        assertTrue("the two share rows ($volumeBottom is past ${CallCardLayout.Y})", volumeBottom > CallCardLayout.Y)
        val (width, height) = LegendLayout.size(widest = 400f, lines = 5)
        val (_, legendY) = LegendLayout.corner(width, height)
        assertTrue("the legend's top ($legendY) is above the pill's bottom", legendY < CallCardLayout.Y + CallCardLayout.HEIGHT)
    }

    @Test
    fun `the volume card wins over the call card, the call card over the legend`() {
        assertEquals(OverlayRules.Drawn(volume = false, call = false, legend = false), OverlayRules.drawn(volume = false, call = false, legend = false))
        assertEquals("a call alone", OverlayRules.Drawn(volume = false, call = true, legend = false), OverlayRules.drawn(volume = false, call = true, legend = false))
        assertEquals("a call and a volume step: only the volume", OverlayRules.Drawn(volume = true, call = false, legend = false), OverlayRules.drawn(volume = true, call = true, legend = false))
        assertEquals("the volume card gone: the call is back", OverlayRules.Drawn(volume = false, call = true, legend = false), OverlayRules.drawn(volume = false, call = true, legend = false))
        assertEquals("a call and the legend: only the call", OverlayRules.Drawn(volume = false, call = true, legend = false), OverlayRules.drawn(volume = false, call = true, legend = true))
        assertEquals("the legend with no call", OverlayRules.Drawn(volume = false, call = false, legend = true), OverlayRules.drawn(volume = false, call = false, legend = true))
        assertEquals("the volume card and the legend with no call both show", OverlayRules.Drawn(volume = true, call = false, legend = true), OverlayRules.drawn(volume = true, call = false, legend = true))
        assertEquals("a call under a volume card still keeps the legend away", OverlayRules.Drawn(volume = true, call = false, legend = false), OverlayRules.drawn(volume = true, call = true, legend = true))
    }

    /** A font of [per] pixels for each character at 20 px, growing with the size. */
    private fun width(per: Float): (String, Float) -> Float = { text, letters -> text.length * per * letters / 20f }

    @Test
    fun `a name that fits keeps the biggest letters`() {
        val fit = CallCardLayout.fitName("Ana", width(10f))
        assertEquals(NameFit("Ana", 23f), fit)
        // 178 px at 23 px letters: 15 characters of 10 px per 20 px letters is 172.5, so it still fits
        assertEquals(NameFit("A".repeat(15), 23f), CallCardLayout.fitName("A".repeat(15), width(10f)))
    }

    @Test
    fun `a name a little too wide is squeezed a pixel of letters at a time down to 19`() {
        // 16 characters: 184 px at 23, 176 at 22 (16 * 10 * 22 / 20 = 176): fits at 22
        assertEquals(NameFit("A".repeat(16), 22f), CallCardLayout.fitName("A".repeat(16), width(10f)))
        // 18 characters: 207 at 23, 198 at 22, 189 at 21, 180 at 20, 171 at 19: fits only at 19
        assertEquals(NameFit("A".repeat(18), 19f), CallCardLayout.fitName("A".repeat(18), width(10f)))
    }

    @Test
    fun `a name too wide even at 19 is cut with an ellipsis at 19 and fits`() {
        val name = "Maria Eduarda da Silva Santos"
        val fit = CallCardLayout.fitName(name, width(10f))
        assertEquals(19f, fit.letters, 0f)
        assertTrue(fit.text, fit.text.endsWith("…"))
        assertTrue(fit.text, name.startsWith(fit.text.removeSuffix("…").trimEnd()))
        assertTrue("fits", width(10f)(fit.text, fit.letters) <= CallCardLayout.TEXT_MAX_W)
        val base = fit.text.removeSuffix("…")
        val longer = width(10f)(name.substring(0, base.length + 2) + "…", fit.letters)
        assertTrue("and two more letters would not: $longer", longer > CallCardLayout.TEXT_MAX_W)
        assertFalse("no space before the ellipsis", fit.text.endsWith(" …"))
    }

    @Test
    fun `an absurdly wide font leaves the ellipsis alone, an empty name stays empty`() {
        assertEquals(NameFit("…", 19f), CallCardLayout.fitName("Ana", width(1_000f)))
        assertEquals(NameFit("", 23f), CallCardLayout.fitName("", width(10f)))
    }

    @Test
    fun `the small line is squeezed sideways to the room and not cut`() {
        assertEquals(1f, CallCardLayout.lineScale(178f), 0f)
        assertEquals(1f, CallCardLayout.lineScale(120f), 0f)
        assertEquals(0.5f, CallCardLayout.lineScale(356f), 0.0001f)
        assertEquals(1f, CallCardLayout.lineScale(0f), 0f)
    }

    @Test
    fun `the line's width counts the shapes of the answer, the decline and the end with their gaps`() {
        val each = { text: String -> text.length * 7f }
        val shape = CallCardLayout.MARK_W + CallCardLayout.MARK_GAP
        assertEquals(7f * 7, CallCardLayout.lineWidth(listOf(CallSpan("Chamado", CallRole.HINT)), each), 0f)
        assertEquals(7f * 4 + shape, CallCardLayout.lineWidth(listOf(CallSpan("Ende", CallRole.END)), each), 0f)
        val ringing = listOf(CallSpan("Atender", CallRole.ANSWER), CallSpan(" · ", CallRole.HINT), CallSpan("Recusar", CallRole.DECLINE))
        assertEquals(7f * 17 + 2 * shape, CallCardLayout.lineWidth(ringing, each), 0.001f)
        assertEquals(12f, shape, 0f)
        assertTrue(CallCardLayout.hasMark(CallRole.ANSWER) && CallCardLayout.hasMark(CallRole.DECLINE) && CallCardLayout.hasMark(CallRole.END))
        assertFalse(CallCardLayout.hasMark(CallRole.HINT))
    }
}
