package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rider's "Anel de manobras" switch: on, the route card carries the maneuver (or the arrival pin with no route); off, the glyph is
 * the code that hides the ring (0xFF) in both cases.
 */
class DashSessionRingToggleTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    /** The glyph (05 02) of the route card now. */
    private fun glyph(): Int {
        val pkt = session.routeCardNow()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == 5 && (pkt[i + 1].toInt() and 0xFF) == 0x02) return pkt[i + 4].toInt() and 0xFF
            i += 4 + len
        }
        error("no glyph in the route card")
    }

    private fun guide() = session.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS)

    @Test
    fun `the screen showing or hiding the maneuver ring is told to the debug text`() {
        val notes = mutableListOf<String>()
        session.onRingChange = { notes += it }
        session.maneuverCircleEnabled = false
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV)
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV) // no change, nothing said
        session.ringAfterScreen(com.reau.app.aa.AaScreen.MENU)
        assertEquals(listOf("maneuver ring shown: screen NAV", "maneuver ring hidden: screen MENU"), notes)
    }

    @Test
    fun `with the ring on the card carries the arrival pin with no route and the maneuver while guiding`() {
        assertEquals(0x3C, glyph())
        guide()
        assertEquals(0x14, glyph())
    }

    @Test
    fun `with the ring off the card hides it with no route and while guiding`() {
        session.maneuverCircleEnabled = false
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        guide()
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
    }

    @Test
    fun `with the ring off a map mode press shows it and a media mode press or the map's middle hides it`() {
        session.maneuverCircleEnabled = false
        assertEquals("hidden until a press", DashCommands.NAV_MANEUVER_NONE, glyph())
        assertEquals(true, session.ringAfterPress(0x13))
        assertEquals("the pin with no route", 0x3C, glyph())
        guide()
        assertEquals("the maneuver while guiding", 0x14, glyph())
        assertEquals(true, session.ringAfterPress(0x05))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        session.ringAfterPress(0x15)
        assertEquals(0x14, glyph())
        assertEquals("the map's middle opens the media mode", true, session.ringAfterPress(0x18))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
    }

    @Test
    fun `with the ring on the presses change nothing, and turning it off starts hidden`() {
        assertEquals(false, session.ringAfterPress(0x05))
        assertEquals(false, session.ringAfterPress(0x13))
        assertEquals(0x3C, glyph())
        session.maneuverCircleEnabled = false
        session.ringAfterPress(0x13)
        session.maneuverCircleEnabled = true
        assertEquals("on again: always", 0x3C, glyph())
        session.maneuverCircleEnabled = false
        assertEquals("off again: hidden until a press", DashCommands.NAV_MANEUVER_NONE, glyph())
    }

    @Test
    fun `with the ring off Waze and Google Maps show it and another screen hides it`() {
        session.maneuverCircleEnabled = false
        assertEquals(true, session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV))
        assertEquals(0x3C, glyph())
        assertEquals("the app menu", true, session.ringAfterScreen(com.reau.app.aa.AaScreen.MENU))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV)
        assertEquals("another app", true, session.ringAfterScreen(com.reau.app.aa.AaScreen.APPS))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV)
        assertEquals("not known: no change", false, session.ringAfterScreen(com.reau.app.aa.AaScreen.UNKNOWN))
        assertEquals(0x3C, glyph())
    }

    /** The info ring on the right shows and hides by the very answer that decides the dash's own ring (its glyph is the hidden one or not). */
    @Test
    fun `the info ring is visible exactly when the maneuver ring is`() {
        fun same(why: String, expected: Boolean) {
            assertEquals(why, expected, glyph() != DashCommands.NAV_MANEUVER_NONE)
            assertEquals(why, expected, session.ringVisible)
        }
        same("the switch on", true)
        session.maneuverCircleEnabled = false
        same("the switch off, hidden until a press", false)
        session.ringAfterPress(0x13)
        same("a map mode press", true)
        session.ringAfterPress(0x05)
        same("a media mode press", false)
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV)
        same("Waze or Google Maps", true)
        session.ringAfterScreen(com.reau.app.aa.AaScreen.MENU)
        same("the app menu", false)
        session.showCaller("Ana")
        same("a call", true)
        session.clearCaller()
        same("the call is over", false)
        session.maneuverCircleEnabled = true
        same("the switch on again", true)
    }

    @Test
    fun `with the ring off the middle switches the ring on a navigation screen, and the menu keeps it hidden until Waze opens`() {
        session.maneuverCircleEnabled = false
        session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV)
        assertEquals(0x3C, glyph())
        assertEquals("the middle hides it", true, session.ringAfterPress(0x18))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        assertEquals("the middle again shows it, on a navigation screen", true, session.ringAfterPress(0x18))
        assertEquals(0x3C, glyph())
        assertEquals(true, session.ringVisible)
        session.ringAfterScreen(com.reau.app.aa.AaScreen.MENU)
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        assertEquals("on the menu the middle does not show it", false, session.ringAfterPress(0x18))
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        assertEquals("Waze opened from the menu", true, session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV))
        assertEquals(0x3C, glyph())
        assertEquals(true, session.ringVisible)
    }

    @Test
    fun `with the ring off a call shows it, ringing or going on, and it goes back to hidden when the call ends`() {
        session.maneuverCircleEnabled = false
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
        session.showCaller("Ana")
        assertEquals("ringing: the pin with no route", 0x3C, glyph())
        guide()
        assertEquals("ringing: the maneuver while guiding", 0x14, glyph())
        session.showCaller("Ana", inCall = true)
        assertEquals("answered: still there", 0x14, glyph())
        session.clearCaller()
        assertEquals("the call is over: hidden again", DashCommands.NAV_MANEUVER_NONE, glyph())
    }

    @Test
    fun `a call over the media mode shows the ring and leaves what a press had chosen when it ends`() {
        session.maneuverCircleEnabled = false
        session.ringAfterPress(0x13)
        session.ringAfterPress(0x05)
        assertEquals("media mode: hidden", DashCommands.NAV_MANEUVER_NONE, glyph())
        session.showCaller("Ana")
        assertEquals(0x3C, glyph())
        session.clearCaller()
        assertEquals(DashCommands.NAV_MANEUVER_NONE, glyph())
    }

    @Test
    fun `with the ring on a call changes nothing`() {
        assertEquals(0x3C, glyph())
        session.showCaller("Ana")
        assertEquals(0x3C, glyph())
        session.clearCaller()
        assertEquals(0x3C, glyph())
    }

    @Test
    fun `with the ring on the screen changes nothing`() {
        assertEquals(false, session.ringAfterScreen(com.reau.app.aa.AaScreen.APPS))
        assertEquals(0x3C, glyph())
        assertEquals(false, session.ringAfterScreen(com.reau.app.aa.AaScreen.NAV))
    }
}
