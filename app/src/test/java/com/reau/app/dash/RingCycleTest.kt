package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The info ring's switch on the joystick: shown or hidden, one step each. */
class RingCycleTest {
    @Test
    fun `only the map mode's down switches the info ring, not the middle, which opens the media mode`() {
        assertEquals(true, RingCycle.advancesOn(0x15))
        assertEquals(0x15, RingCycle.RING_PRESS)
        for (other in listOf(0x18, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x12, 0x13, 0x14)) {
            assertEquals("0x%02X".format(other), false, RingCycle.advancesOn(other))
        }
    }

    @Test
    fun `step 0 shows the info ring and step 1 hides it`() {
        assertEquals(true, RingCycle.info(0))
        assertEquals(false, RingCycle.info(1))
        assertEquals(2, RingCycle.STEPS)
    }

    @Test
    fun `a press goes from shown to hidden and back`() {
        assertEquals(1, RingCycle.next(0))
        assertEquals(0, RingCycle.next(1))
    }

    @Test
    fun `a step out of range, or saved by an older build, counts as hidden`() {
        assertEquals(0, RingCycle.valid(0))
        assertEquals(1, RingCycle.valid(1))
        assertEquals(1, RingCycle.valid(2))
        assertEquals(1, RingCycle.valid(-4))
        assertEquals(1, RingCycle.valid(9))
        assertEquals("hidden goes to shown", 0, RingCycle.next(2))
        assertEquals(false, RingCycle.info(2))
    }
}
