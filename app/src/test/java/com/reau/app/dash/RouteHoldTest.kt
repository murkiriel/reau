package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route card held back after the dash's own "Exit navigation?" ([RouteHold]): nothing held until the exit, the "navigation off" sent in the same step that starts
 * the hold, and the hold ended only by a new guidance: with a guidance active at the exit, an inactive spell of 10 s or more and then an active one, never the guidance Android Auto goes on
 * with or a recalculation; with none active at the exit, the first active one. The hold keeps its own view of whether the guidance is active.
 */
class RouteHoldTest {
    private val hold = RouteHold()
    private val sent = mutableListOf<String>()

    private fun card() = hold.sendUnlessHeld { sent += "card" }

    /** The exit while Android Auto's guidance is active (it was active at [at]). */
    private fun exitGuiding(at: Long = 0, send: () -> Boolean = { true }) {
        hold.active(at)
        hold.hold(send)
    }

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
        hold.active(500)
        assertTrue(
            hold.hold {
                sent += "nav off"
                true
            },
        )
        assertTrue(hold.isHeld)
        assertFalse(card())
        assertEquals(listOf("nav off"), sent)
    }

    @Test
    fun `the guidance Android Auto goes on with is not a new one, however long`() {
        exitGuiding(1_000)
        for (at in listOf(2_000L, 30_000L, 600_000L)) assertFalse("at $at", hold.active(at))
        assertTrue(hold.isHeld)
        assertFalse(card())
    }

    @Test
    fun `a recalculation, an inactive spell shorter than ten seconds, is not a new guidance`() {
        exitGuiding()
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
        exitGuiding()
        hold.inactive(5_000)
        assertTrue(hold.active(5_000 + RouteHold.NEW_GUIDANCE_AFTER_MS))
        assertFalse(hold.isHeld)
        assertTrue(card())
        assertFalse("it ends once", hold.active(20_000))
    }

    @Test
    fun `the time without a guidance counts from its first inactive message, not the last`() {
        exitGuiding()
        hold.inactive(1_000)
        hold.inactive(9_000)
        hold.inactive(10_500)
        assertTrue(hold.active(11_000))
    }

    @Test
    fun `an exit with no guidance is ended by the first active guidance, however soon it comes`() {
        hold.hold { true }
        assertTrue(hold.isHeld)
        assertFalse(card())
        assertTrue("the first one ends it, with no ten seconds to wait", hold.active(2_000))
        assertFalse(hold.isHeld)
        assertTrue(card())
        assertFalse("it ends once", hold.active(3_000))
    }

    @Test
    fun `an exit with no guidance after one that ended is ended by the next active guidance too`() {
        hold.active(0)
        hold.inactive(1_000)
        hold.hold { true }
        assertTrue(hold.active(1_500))
        assertFalse(hold.isHeld)
    }

    @Test
    fun `a second exit in the middle of a recalculation is ended by the guidance that returns`() {
        exitGuiding(0)
        hold.inactive(5_000)
        hold.hold { true }
        assertTrue(hold.active(6_000))
    }

    @Test
    fun `a second exit with the guidance active still waits for the ten seconds`() {
        exitGuiding(0)
        hold.hold { true }
        assertFalse(hold.active(1_000))
        assertTrue(hold.isHeld)
        hold.inactive(2_000)
        assertTrue(hold.active(2_000 + RouteHold.NEW_GUIDANCE_AFTER_MS))
    }

    @Test
    fun `the hold knows the guidance ended even when nothing was held at the time`() {
        hold.active(0)
        hold.inactive(1_000)
        assertFalse(hold.isHeld)
        hold.hold { true }
        assertTrue("no guidance at the exit: the first active one ends it", hold.active(2_000))
    }

    @Test
    fun `the exit tells whether the navigation off went, and the hold is on either way`() {
        assertFalse(hold.hold { false })
        assertTrue(hold.isHeld)
        assertFalse(card())
    }

    @Test
    fun `an inactive message with nothing held starts no clock for a later hold`() {
        hold.active(0)
        hold.inactive(1_000)
        exitGuiding(2_000)
        assertFalse("the earlier inactive does not count", hold.active(20_000))
        assertTrue(hold.isHeld)
    }

    @Test
    fun `the end of the link lets everything go`() {
        hold.hold { true }
        hold.reset()
        assertFalse(hold.isHeld)
        assertTrue(card())
        assertFalse("nothing is waiting for a guidance any more", hold.active(1_000))
    }
}
