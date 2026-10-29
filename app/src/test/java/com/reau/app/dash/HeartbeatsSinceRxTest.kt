package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Test

/** The RX watchdog must not take a pause of the phone for the dash's silence: it counts the heartbeats the phone really sent. */
class HeartbeatsSinceRxTest {
    @Test
    fun `it counts one per turn while the dash says nothing`() {
        val c = HeartbeatsSinceRx()
        assertEquals(1, c.tick(lastRxMs = 5_000))
        assertEquals(2, c.tick(5_000))
        assertEquals(3, c.tick(5_000))
    }

    @Test
    fun `a new packet from the dash starts the count again`() {
        val c = HeartbeatsSinceRx()
        repeat(3) { c.tick(5_000) }
        assertEquals(1, c.tick(8_000))
        assertEquals(2, c.tick(8_000))
    }

    @Test
    fun `a phone that slept for ten seconds is one turn after it, not ten`() {
        val c = HeartbeatsSinceRx()
        c.tick(1_000)
        // the loop did not run for ten seconds: the dash may well have spoken, and the count is still of turns, not of time
        assertEquals(2, c.tick(1_000))
    }
}
