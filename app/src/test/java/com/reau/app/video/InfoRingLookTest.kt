package com.reau.app.video

import com.reau.app.weather.RingTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the dash's info ring sits and what colour its parts are: the measures of the design, with no drawing. */
class InfoRingLookTest {
    @Test
    fun `the ring is the mirror of the dash's maneuver bubble, on the right where the dash draws nothing`() {
        assertEquals(DashGlass.FRAME_W - DashMask.BUBBLE_X.toInt(), InfoRingSpot.CENTER_X)
        assertEquals(441, InfoRingSpot.CENTER_X)
        assertEquals(DashMask.BUBBLE_Y.toInt(), InfoRingSpot.CENTER_Y)
        assertEquals(DashMask.BUBBLE_R, InfoRingSpot.RADIUS, 0f)
        assertEquals(381, InfoRingSpot.X)
        assertEquals(173, InfoRingSpot.Y)
        assertEquals(120, InfoRingSpot.SIZE)
    }

    @Test
    fun `the whole ring is on the glass the rider sees`() {
        var checked = 0
        for (degrees in 0 until 360) {
            val a = Math.toRadians(degrees.toDouble())
            val x = InfoRingSpot.CENTER_X + InfoRingSpot.RADIUS * Math.cos(a)
            val y = InfoRingSpot.CENTER_Y + InfoRingSpot.RADIUS * Math.sin(a)
            assertTrue("$degrees degrees at ($x, $y)", DashGlass.isVisible(x, y))
            checked++
        }
        assertEquals(360, checked)
        assertEquals("the box is wholly in the frame", true, InfoRingSpot.X + InfoRingSpot.SIZE <= DashGlass.FRAME_W && InfoRingSpot.Y + InfoRingSpot.SIZE <= DashGlass.FRAME_H)
    }

    @Test
    fun `the ring does not meet the maneuver bubble`() {
        val dx = InfoRingSpot.CENTER_X - DashMask.BUBBLE_X
        assertTrue(dx > InfoRingSpot.RADIUS + DashMask.BUBBLE_R)
    }

    @Test
    fun `the outline and the band sit inside the disk, the band inside the outline`() {
        assertEquals(60f, InfoRingSpot.OUTLINE_RADIUS + InfoRingSpot.OUTLINE_WIDTH / 2, 0f)
        assertEquals(57f, InfoRingSpot.BAND_RADIUS + InfoRingSpot.BAND_WIDTH / 2, 0f)
        assertEquals(51f, InfoRingSpot.BAND_RADIUS - InfoRingSpot.BAND_WIDTH / 2, 0f)
        assertTrue(InfoRingSpot.CONTENT_RADIUS < InfoRingSpot.BAND_RADIUS - InfoRingSpot.BAND_WIDTH / 2)
    }

    @Test
    fun `a stale ring's dashes are sixteen to the circle, two thirds dash`() {
        fun dashes(radius: Float, dash: FloatArray) = (2 * Math.PI * radius / (dash[0] + dash[1])).toFloat()
        assertEquals(16f, dashes(InfoRingSpot.OUTLINE_RADIUS, InfoRingSpot.OUTLINE_DASH), 0.01f)
        assertEquals(16f, dashes(InfoRingSpot.BAND_RADIUS, InfoRingSpot.BAND_DASH), 0.01f)
        assertEquals(0.65f, InfoRingSpot.OUTLINE_DASH[0] / (InfoRingSpot.OUTLINE_DASH[0] + InfoRingSpot.OUTLINE_DASH[1]), 0.01f)
    }

    @Test
    fun `the line keeps its size when it fits and shrinks all its pieces together when it does not`() {
        assertEquals(1f, InfoRingSpot.lineScale(listOf(40f)), 0f)
        assertEquals(1f, InfoRingSpot.lineScale(listOf(20f, 30f, 22f)), 0f)
        // 20 + 30 + 40 + two gaps of 4 = 98: shrunk to 80 wide.
        assertEquals(80f / 98f, InfoRingSpot.lineScale(listOf(20f, 30f, 40f)), 0.0001f)
        assertEquals(1f, InfoRingSpot.lineScale(emptyList()), 0f)
    }

    @Test
    fun `the palette is the design's, day and night`() {
        assertEquals(0xFFF3EDE0.toInt(), InfoRingPalette.DAY.disk)
        assertEquals(0xFF121314.toInt(), InfoRingPalette.NIGHT.disk)
        assertEquals(InfoRingPalette.DAY.disk, InfoRingPalette.NIGHT.ink)
        assertEquals(InfoRingPalette.DAY.ink, InfoRingPalette.of(false).ink)
        assertEquals(InfoRingPalette.NIGHT, InfoRingPalette.of(true))
        assertNotEquals("the night outline is the paler one", InfoRingPalette.DAY.outline, InfoRingPalette.NIGHT.outline)
    }

    @Test
    fun `danger fills the disk red with white ink and has no band, the others keep the disk and a band`() {
        for (look in listOf(InfoRingPalette.DAY, InfoRingPalette.NIGHT)) {
            assertEquals(look.danger, look.diskFor(RingTone.DANGER))
            assertEquals(InfoRingPalette.WHITE, look.inkFor(RingTone.DANGER))
            assertNull(look.bandFor(RingTone.DANGER))
            assertEquals(look.disk, look.diskFor(RingTone.RAIN))
            assertEquals(look.ink, look.inkFor(RingTone.RAIN))
            assertNull(look.bandFor(RingTone.NONE))
            assertEquals(look.rain, look.bandFor(RingTone.RAIN))
            assertEquals(look.attention, look.bandFor(RingTone.ATTENTION))
            assertEquals(look.alert, look.bandFor(RingTone.ALERT))
        }
    }

    @Test
    fun `the UV and air bands have the colours of their scales, the same day and night`() {
        for (tone in RingTone.entries.filter { it.name.startsWith("UV_") || it.name.startsWith("AIR_") }) {
            assertEquals(tone.name, InfoRingPalette.DAY.bandFor(tone), InfoRingPalette.NIGHT.bandFor(tone))
        }
        assertEquals(0xFFE36A00.toInt(), InfoRingPalette.DAY.bandFor(RingTone.UV_HIGH))
        assertEquals(0xFFD0102B.toInt(), InfoRingPalette.DAY.bandFor(RingTone.UV_VERY_HIGH))
        assertEquals(0xFF7B3FB5.toInt(), InfoRingPalette.DAY.bandFor(RingTone.UV_EXTREME))
        assertEquals(0xFF7E0023.toInt(), InfoRingPalette.DAY.bandFor(RingTone.AIR_HAZARDOUS))
        assertEquals(InfoRingPalette.DAY.bandFor(RingTone.UV_HIGH), InfoRingPalette.DAY.bandFor(RingTone.AIR_SENSITIVE))
    }
}
