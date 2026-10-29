package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the side bar's shortcuts are in Android Auto's video (800×480): the two apps' shortcuts yes, the microphone and the menu's button no. */
class AaRailTest {
    @Test
    fun `the two shortcuts of the bar, the navigation app's above the media app's`() {
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(755, 199))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(755, 277))
    }

    @Test
    fun `the microphone and the menu's button are not shortcuts`() {
        assertNull("microphone", AaRail.slot(755, 353))
        assertNull("menu's button", AaRail.slot(755, 431))
    }

    @Test
    fun `the picture is not the bar`() {
        assertNull(AaRail.slot(400, 199))
        assertNull(AaRail.slot(AaRail.X_FROM - 1, 199))
    }

    @Test
    fun `the edges of the shortcuts' zones`() {
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(AaRail.X_FROM, AaRail.Y_FROM))
        assertNull(AaRail.slot(AaRail.X_FROM, AaRail.Y_FROM - 1))
        assertEquals(AaRail.Slot.NAVIGATION, AaRail.slot(799, AaRail.Y_SPLIT - 1))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(799, AaRail.Y_SPLIT))
        assertEquals(AaRail.Slot.MEDIA, AaRail.slot(799, AaRail.Y_TO - 1))
        assertNull(AaRail.slot(799, AaRail.Y_TO))
    }
}
