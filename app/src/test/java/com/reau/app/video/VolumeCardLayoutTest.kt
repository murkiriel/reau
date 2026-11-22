package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Where the volume card goes on the dash's 526×300 frame and how it is measured ([VolumeCardLayout]; the drawing itself is a bitmap that the unit tests
 * cannot make): at the top, centred, whole on the round glass with room to spare, clear of the joystick's legend in the middle, and the bar's fill.
 */
class VolumeCardLayoutTest {
    @Test
    fun `the pill is 154 by 48, centred across and 12 px from the top`() {
        assertEquals(154, VolumeCardLayout.WIDTH)
        assertEquals(48, VolumeCardLayout.HEIGHT)
        assertEquals(186, VolumeCardLayout.X)
        assertEquals(DashGlass.FRAME_W / 2 - 77, VolumeCardLayout.X)
        assertEquals(12, VolumeCardLayout.Y)
        assertEquals("centred", DashGlass.FRAME_W - (VolumeCardLayout.X + VolumeCardLayout.WIDTH), VolumeCardLayout.X)
    }

    @Test
    fun `the four rounded corners are at least 4 px inside the glass`() {
        val r = VolumeCardLayout.RADIUS.toDouble()
        val reach = r * cos(Math.PI / 4)
        val left = VolumeCardLayout.X.toDouble()
        val right = left + VolumeCardLayout.WIDTH
        val top = VolumeCardLayout.Y.toDouble()
        val bottom = top + VolumeCardLayout.HEIGHT
        // The farthest point of each rounded corner: the circle of the corner (radius 12) at 45 degrees outward.
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
        assertEquals("all of the pill shows", 1.0, DashGlass.visibleFraction(RectPx(VolumeCardLayout.X, VolumeCardLayout.Y, VolumeCardLayout.WIDTH, VolumeCardLayout.HEIGHT)) , 0.0001)
    }

    @Test
    fun `everything in the pill is inside it and the bar is under the words`() {
        assertTrue(VolumeCardLayout.ICON_X + VolumeCardLayout.ICON <= VolumeCardLayout.TEXT_X)
        assertTrue(VolumeCardLayout.ICON_Y + VolumeCardLayout.ICON <= VolumeCardLayout.HEIGHT)
        assertTrue("the words fit before the right edge", VolumeCardLayout.TEXT_X + VolumeCardLayout.TEXT_MAX_W <= VolumeCardLayout.WIDTH)
        assertTrue("the bar is inside", VolumeCardLayout.BAR_X + VolumeCardLayout.BAR_W <= VolumeCardLayout.WIDTH)
        assertTrue(VolumeCardLayout.BAR_Y + VolumeCardLayout.BAR_H <= VolumeCardLayout.HEIGHT)
        assertTrue("the words' baseline is above the bar", VolumeCardLayout.BASELINE < VolumeCardLayout.BAR_Y)
        assertEquals(130, VolumeCardLayout.BAR_W)
    }

    @Test
    fun `the fill is none at 0, half at half, all the track at the most, and never more`() {
        assertEquals(0, VolumeCardLayout.fillWidth(0, 15))
        assertEquals(65, VolumeCardLayout.fillWidth(5, 10))
        assertEquals(130, VolumeCardLayout.fillWidth(15, 15))
        assertEquals(130, VolumeCardLayout.fillWidth(40, 15))
        assertEquals(0, VolumeCardLayout.fillWidth(-3, 15))
        assertEquals(0, VolumeCardLayout.fillWidth(3, 0))
        assertEquals(60, VolumeCardLayout.fillWidth(7, 15))
    }

    @Test
    fun `words wider than 100 px are squeezed to 100 and not cut`() {
        assertEquals(1f, VolumeCardLayout.textScale(100f), 0f)
        assertEquals(1f, VolumeCardLayout.textScale(60f), 0f)
        assertEquals(0.5f, VolumeCardLayout.textScale(200f), 0.0001f)
        assertEquals(1f, VolumeCardLayout.textScale(0f), 0f)
    }

    @Test
    fun `the card does not touch the joystick's legend in the middle of the frame, so the two can show together`() {
        val (width, height) = LegendLayout.size(widest = 400f, lines = 5)
        val (x, y) = LegendLayout.corner(width, height)
        assertTrue("the legend starts at $y, the card ends at ${VolumeCardLayout.Y + VolumeCardLayout.HEIGHT}", y > VolumeCardLayout.Y + VolumeCardLayout.HEIGHT)
        assertTrue(x >= 0)
    }
}
