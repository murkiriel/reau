package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title of the route card (the first field, 05 01) after a joystick tap (P30 Eco): the name of the action for 1.5 s, in the card with no
 * route and while guiding, replaced by the next one, then back to the street or to "REau". The clock is the test's own.
 */
class DashSessionEchoTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)
    private var now = 10_000L

    init {
        session.echo = TimedShow(ActionEcho.SHOW_MS, nowMs = { now })
    }

    @After
    fun tearDown() = scope.cancel()

    /** The title (05 01) of the route card now, as the dash reads it: ISO-8859-1 up to the zero that ends it. */
    private fun title(): String {
        val pkt = session.routeCardNow()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == 5 && (pkt[i + 1].toInt() and 0xFF) == 0x01) {
                return String(pkt, i + 4, len - 1, Charsets.ISO_8859_1)
            }
            i += 4 + len
        }
        error("no title in the route card")
    }

    private fun guide(road: String) =
        session.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = road)

    @Test
    fun `with no echo the title is REau, or the street while guiding`() {
        assertEquals("REau", title())
        guide("Rua das Flores")
        assertEquals("Rua das Flores", title())
    }

    @Test
    fun `a tap's name is the title for 1500 ms and then REau is back`() {
        assertTrue(session.echoAction("Next song"))
        assertEquals("Next song", title())
        now += 1_499
        assertEquals("still there a millisecond before", "Next song", title())
        now += 1
        assertEquals("REau", title())
    }

    @Test
    fun `while guiding the name takes the street's place and the street comes back`() {
        guide("Rua das Flores")
        session.echoAction("Back")
        assertEquals("Back", title())
        now += 1_500
        assertEquals("Rua das Flores", title())
    }

    @Test
    fun `a tap after another takes its place and the 1500 ms start again`() {
        session.echoAction("Next item")
        now += 1_000
        session.echoAction("Confirm")
        assertEquals("Confirm", title())
        now += 1_400
        assertEquals("2400 ms after the first, 1400 after the last", "Confirm", title())
        now += 100
        assertEquals("REau", title())
    }

    @Test
    fun `with the echo switched off nothing is taken and the title stays`() {
        session.actionEcho = false
        assertFalse(session.actionEcho)
        assertFalse(session.echoAction("Next song"))
        assertEquals("REau", title())
        session.actionEcho = true
        assertTrue(session.echoAction("Next song"))
        assertEquals("Next song", title())
    }

    @Test
    fun `switching the echo off while it shows takes it away at once`() {
        session.echoAction("Next song")
        session.actionEcho = false
        assertEquals("REau", title())
    }

    @Test
    fun `an empty name is no echo`() {
        assertFalse(session.echoAction("   "))
        assertEquals("REau", title())
    }

    @Test
    fun `the accents of a name reach the card as the Latin-1 letters the dash reads`() {
        session.echoAction("Próxima música")
        assertEquals("Próxima música", title())
        val bytes = session.routeCardNow()
        assertTrue("ó as the one byte F3", bytes.any { (it.toInt() and 0xFF) == 0xF3 })
        assertTrue("ú as the one byte FA", bytes.any { (it.toInt() and 0xFF) == 0xFA })
    }

    @Test
    fun `the echo is on by default`() {
        assertTrue(DashSession(scope).actionEcho)
    }
}
