package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route card held back after the dash's own "Exit navigation?" ([RouteHold]): nothing held until the exit, the "navigation off" sent in the same step that starts
 * the hold, and the hold ended only by a new guidance (an inactive one of 10 s or more, then an active one), never by the guidance Android Auto goes on with or a recalculation.
 */
class RouteHoldTest {
    private val hold = RouteHold()
    private val sent = mutableListOf<String>()

    private fun card() = hold.sendUnlessHeld { sent += "card" }

    @Test
    fun `with no exit the card and the packet always go`() {
        assertFalse(hold.isHeld)
        assertTrue(card())
        assertTrue(card())
        assertEquals(listOf("card", "card"), sent)
        assertFalse("an active guidance is nothing to end", hold.active(1_000))
    }

    @Test
    fun `the exit sends the navigation off and then the card no longer goes`() {
        hold.hold(guiding = true, nowMs = 1_000) { sent += "nav off" }
        assertTrue(hold.isHeld)
        assertFalse(card())
        assertEquals(listOf("nav off"), sent)
    }

    @Test
    fun `the guidance Android Auto goes on with is not a new one, however long`() {
        hold.hold(guiding = true, nowMs = 1_000) {}
        for (at in listOf(2_000L, 30_000L, 600_000L)) assertFalse("at $at", hold.active(at))
        assertTrue(hold.isHeld)
        assertFalse(card())
    }

    @Test
    fun `a recalculation, an inactive spell shorter than ten seconds, is not a new guidance`() {
        hold.hold(guiding = true, nowMs = 0) {}
        hold.inactive(5_000)
        assertFalse(hold.active(5_000 + RouteHold.NEW_GUIDANCE_AFTER_MS - 1))
        assertTrue(hold.isHeld)
        // The spell was forgotten: the next one counts from its own start.
        hold.inactive(30_000)
        assertFalse(hold.active(35_000))
        assertTrue(hold.isHeld)
    }

    @Test
    fun `an inactive spell of ten seconds or more and then an active guidance is a new one, and the card goes again`() {
        hold.hold(guiding = true, nowMs = 0) {}
        hold.inactive(5_000)
        assertTrue(hold.active(5_000 + RouteHold.NEW_GUIDANCE_AFTER_MS))
        assertFalse(hold.isHeld)
        assertTrue(card())
        assertFalse("it ends once", hold.active(20_000))
    }

    @Test
    fun `the time without a guidance counts from its first inactive message, not the last`() {
        hold.hold(guiding = true, nowMs = 0) {}
        hold.inactive(1_000)
        hold.inactive(9_000)
        hold.inactive(10_500)
        assertTrue(hold.active(11_000))
    }

    @Test
    fun `an exit with no guidance counts the time from the exit itself`() {
        hold.hold(guiding = false, nowMs = 2_000) {}
        assertFalse(hold.active(2_000 + RouteHold.NEW_GUIDANCE_AFTER_MS - 1))
        hold.hold(guiding = false, nowMs = 50_000) {}
        assertTrue(hold.active(50_000 + RouteHold.NEW_GUIDANCE_AFTER_MS))
    }

    @Test
    fun `inactive with nothing held changes nothing`() {
        hold.inactive(1_000)
        assertFalse(hold.isHeld)
        hold.hold(guiding = true, nowMs = 2_000) {}
        assertFalse("the earlier inactive does not count", hold.active(20_000))
    }

    @Test
    fun `the end of the link lets everything go`() {
        hold.hold(guiding = false, nowMs = 0) {}
        hold.reset()
        assertFalse(hold.isHeld)
        assertTrue(card())
    }
}
