package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android Auto's process crashed 0.64 to 1.19 s after REau's bye in three of nine stops, with its settings opened within 126 ms of it. */
class StopWalkGateTest {
    @Test
    fun `right after the session ended the walk waits the whole settle time`() {
        assertEquals(StopWalkGate.SETTLE_MS, StopWalkGate.waitMs(0))
        assertEquals(1_874L, StopWalkGate.waitMs(126)) // the real case: the settings opened 126 ms after the bye
    }

    @Test
    fun `the wait shrinks with the time that already passed and ends at the settle time`() {
        assertEquals(1_000L, StopWalkGate.waitMs(1_000))
        assertEquals(1L, StopWalkGate.waitMs(1_999))
        assertEquals(0L, StopWalkGate.waitMs(2_000))
        assertEquals(0L, StopWalkGate.waitMs(60_000))
    }

    @Test
    fun `a clock that went back never makes it wait longer than the settle time`() {
        assertEquals(StopWalkGate.SETTLE_MS, StopWalkGate.waitMs(-5_000))
    }

    @Test
    fun `the settle time is longer than the 1_2 seconds in which the crashes came`() {
        org.junit.Assert.assertTrue(StopWalkGate.SETTLE_MS > 1_190L)
    }
}
