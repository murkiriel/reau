package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring with the switch off follows the joystick: shown by the map mode's presses, hidden by the media mode's, and switched by the map's middle. */
class RingByPressTest {
    private val ring = RingByPress()

    @Test
    fun `it starts hidden`() = assertFalse(ring.shown)

    @Test
    fun `every press of the map mode shows it`() {
        for (code in listOf(0x13, 0x14, 0x15, 0x12)) {
            ring.reset()
            assertTrue("0x%02X changes it".format(code), ring.press(code))
            assertTrue("0x%02X".format(code), ring.shown)
        }
    }

    @Test
    fun `every press of the media mode and the map's middle hide it`() {
        for (code in listOf(0x18, 0x05, 0x06, 0x07, 0x09, 0x0A)) {
            ring.press(0x13)
            assertTrue(ring.shown)
            assertTrue("0x%02X changes it".format(code), ring.press(code))
            assertFalse("0x%02X".format(code), ring.shown)
        }
    }

    @Test
    fun `the middle hides a ring that is shown, and shows it again on a navigation screen`() {
        ring.screen(true)
        assertTrue(ring.shown)
        assertTrue("shown: it hides", ring.press(0x18))
        assertFalse(ring.shown)
        assertTrue("hidden, on a navigation screen: it shows", ring.press(0x18))
        assertTrue(ring.shown)
        assertTrue("and it hides again", ring.press(0x18))
        assertFalse(ring.shown)
    }

    @Test
    fun `the middle does not show the ring on the menu, on another app or on a screen not known`() {
        ring.screen(false)
        assertFalse("the app menu", ring.press(0x18))
        assertFalse(ring.shown)
        val unknown = RingByPress()
        assertFalse("no screen known yet", unknown.press(0x18))
        assertFalse(unknown.shown)
        unknown.screen(null)
        assertFalse("a screen not known changes nothing", unknown.press(0x18))
    }

    @Test
    fun `opening Waze from the menu shows the ring, and then the middle is a switch again`() {
        ring.screen(true)
        ring.press(0x18) // hidden by the middle
        ring.screen(false) // into the app menu
        assertFalse("the middle in the menu does nothing", ring.press(0x18))
        assertTrue("opening Waze shows it", ring.screen(true))
        assertTrue(ring.shown)
        assertTrue("the middle hides it", ring.press(0x18))
        assertTrue("and shows it again", ring.press(0x18))
    }

    @Test
    fun `the media mode's buttons only hide it, they never show it`() {
        for (code in listOf(0x05, 0x06, 0x07, 0x09, 0x0A)) {
            ring.reset()
            ring.screen(true)
            assertTrue("0x%02X hides".format(code), ring.press(code))
            assertFalse(ring.shown)
            assertFalse("0x%02X does not show it again".format(code), ring.press(code))
        }
    }

    @Test
    fun `a navigation screen shows the ring and another screen hides it, an unknown one changes nothing`() {
        assertTrue(ring.screen(true))
        assertTrue(ring.shown)
        assertFalse("shown already", ring.screen(true))
        assertFalse("not known", ring.screen(null))
        assertTrue(ring.shown)
        assertTrue(ring.screen(false))
        assertFalse(ring.shown)
        assertFalse("hidden already", ring.screen(false))
    }

    @Test
    fun `a press goes on from the screen's state`() {
        ring.screen(false)
        assertTrue("a press of the map mode shows it on any screen", ring.press(0x13))
        ring.screen(true)
        assertTrue("the media mode hides it on a navigation screen", ring.press(0x05))
        assertFalse(ring.shown)
    }

    @Test
    fun `a press that says nothing about the mode changes nothing, and a repeat is no change`() {
        ring.press(0x13)
        for (code in listOf(0x0B, 0x01, 0x02, 0x03, 0x04, 0x16, 0x22)) {
            assertFalse("0x%02X".format(code), ring.press(code))
            assertTrue(ring.shown)
        }
        assertFalse("shown already", ring.press(0x14))
        ring.press(0x05)
        assertFalse("hidden already", ring.press(0x06))
        assertEquals(false, ring.shown)
    }
}
