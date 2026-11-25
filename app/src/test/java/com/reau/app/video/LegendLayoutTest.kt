package com.reau.app.video

import com.reau.app.aa.JoystickBindings.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the joystick's legend goes on the dash's 526×300 frame and how big it is (P30 Eco; the drawing itself is a bitmap that the unit tests cannot
 * make): in the middle, whole on the round glass, about a quarter of the frame, and the arrows pointing where they say.
 */
class LegendLayoutTest {
    private val frame = DashGlass.FRAME_W * DashGlass.FRAME_H

    @Test
    fun `the card is as tall as its lines and as wide as its widest name`() {
        val (width, height) = LegendLayout.size(widest = 190f, lines = 5)
        assertEquals(LegendLayout.TEXT_X + 190 + LegendLayout.PAD_X, width)
        assertEquals(2 * LegendLayout.PAD_Y + 5 * LegendLayout.ROW_H, height)
        assertEquals("a card of two lines is shorter", 2 * LegendLayout.PAD_Y + 2 * LegendLayout.ROW_H, LegendLayout.size(190f, 2).second)
    }

    @Test
    fun `a card of five lines with a name of 19 wide letters takes no more than 30 percent of the frame, so the map stays in view`() {
        val (width, height) = LegendLayout.size(widest = 19 * 12f, lines = 5)
        assertTrue("$width x $height", width * height <= frame * 30 / 100)
    }

    @Test
    fun `a name that is too wide is squeezed to fit and not cut, and the card never passes the largest width`() {
        val room = (LegendLayout.MAX_W - LegendLayout.TEXT_X - LegendLayout.PAD_X).toFloat()
        assertEquals(1f, LegendLayout.textScale(room), 0f)
        assertEquals(1f, LegendLayout.textScale(10f), 0f)
        assertEquals(0.5f, LegendLayout.textScale(2 * room), 0.0001f)
        assertEquals(LegendLayout.MAX_W, LegendLayout.size(2 * room, 5).first)
        assertTrue(LegendLayout.size(room, 5).first <= LegendLayout.MAX_W)
    }

    @Test
    fun `the card is in the middle of the frame, whole on the round glass, at any width it can have`() {
        for (widest in listOf(40f, 150f, 230f, 400f)) {
            val (width, height) = LegendLayout.size(widest, 5)
            val (x, y) = LegendLayout.corner(width, height)
            assertEquals("centred across", (DashGlass.FRAME_W - width) / 2, x)
            assertEquals("centred down", (DashGlass.FRAME_H - height) / 2, y)
            assertEquals("whole on the glass for $widest", 1.0, DashGlass.visibleFraction(RectPx(x, y, width, height)), 0.0)
        }
    }

    @Test
    fun `the lines sit one under the other, each inside the card, the first under the top margin`() {
        val (_, height) = LegendLayout.size(100f, 5)
        val centers = (0 until 5).map { LegendLayout.rowCenterY(it) }
        assertEquals(LegendLayout.PAD_Y + LegendLayout.ROW_H / 2f, centers.first(), 0f)
        for ((a, b) in centers.zipWithNext()) assertEquals(LegendLayout.ROW_H.toFloat(), b - a, 0f)
        assertTrue(centers.last() + LegendLayout.ROW_H / 2f + LegendLayout.PAD_Y <= height)
    }

    @Test
    fun `the marker sits left of the words and inside the margin`() {
        assertTrue(LegendLayout.MARKER_CENTER_X - LegendLayout.MARKER / 2f >= LegendLayout.PAD_X)
        assertTrue(LegendLayout.MARKER_CENTER_X + LegendLayout.MARKER / 2f < LegendLayout.TEXT_X)
    }

    @Test
    fun `each arrow points where it says, with its tip first and inside its box`() {
        val half = LegendLayout.MARKER / 2f
        val up = LegendLayout.arrow(Control.UP, 50f, 50f)
        val down = LegendLayout.arrow(Control.DOWN, 50f, 50f)
        val left = LegendLayout.arrow(Control.LEFT, 50f, 50f)
        val right = LegendLayout.arrow(Control.RIGHT, 50f, 50f)
        assertEquals(50f to 50f - half, up.first())
        assertEquals(50f to 50f + half, down.first())
        assertEquals(50f - half to 50f, left.first())
        assertEquals(50f + half to 50f, right.first())
        for (corners in listOf(up, down, left, right)) {
            assertEquals(3, corners.size)
            for ((x, y) in corners) assertTrue("$x $y", x in 50f - half..50f + half && y in 50f - half..50f + half)
        }
        assertTrue("up: the base is below the tip", up.drop(1).all { it.second > up.first().second })
        assertTrue("down: the base is above the tip", down.drop(1).all { it.second < down.first().second })
        assertTrue("left: the base is right of the tip", left.drop(1).all { it.first > left.first().first })
        assertTrue("right: the base is left of the tip", right.drop(1).all { it.first < right.first().first })
    }

    @Test
    fun `the middle is a dot, with no arrow`() {
        assertTrue(LegendLayout.arrow(Control.CENTER, 50f, 50f).isEmpty())
    }
}
