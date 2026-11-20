package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the side bar's shortcuts are in Android Auto's video (800×480): the two apps' shortcuts yes, the microphone and the grid's button no. In
 * Android Auto 18 the bar is at the left (x 0 to ~90): shortcuts at (45, 198) and (45, 275), microphone (45, 352), grid's button (45, 430).
 * (Android Auto 17 had it at the right, from x 700; the first version of these tests was measured there.)
 */
class AaRailTest {
    @Test
    fun `the two shortcuts of the bar, the navigation app's above the media app's`() {
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(45, 198))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(45, 275))
    }

    @Test
    fun `the microphone and the grid's button are not shortcuts`() {
        assertNull("microphone", AaRail.slot(45, 352))
        assertNull("grid's button", AaRail.slot(45, 430))
    }

    @Test
    fun `the picture is not the bar, and neither is the right edge where Android Auto 17 had it`() {
        assertNull(AaRail.slot(400, 198))
        assertNull(AaRail.slot(AaRail.X_TO, 198))
        assertNull("the old bar", AaRail.slot(755, 198))
        assertNull("the old bar", AaRail.slot(755, 275))
    }

    @Test
    fun `the edges of the shortcuts' zones`() {
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(0, AaRail.Y_FROM))
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(AaRail.X_TO - 1, AaRail.Y_FROM))
        assertNull(AaRail.slot(AaRail.X_TO, AaRail.Y_FROM))
        assertNull(AaRail.slot(-1, AaRail.Y_FROM))
        assertNull(AaRail.slot(0, AaRail.Y_FROM - 1))
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(0, AaRail.Y_SPLIT - 1))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(0, AaRail.Y_SPLIT))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(AaRail.X_TO - 1, AaRail.Y_TO - 1))
        assertNull(AaRail.slot(0, AaRail.Y_TO))
    }
}
