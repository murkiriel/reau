package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsSleepTest {
    @Test
    fun `a sleep that is interrupted gives the interruption back to the thread`() {
        Thread.currentThread().interrupt()
        val slept = Utils.ms_sleep(5_000)
        assertEquals("it left at once, having slept nothing", 0L, slept)
        assertTrue("and the flag is still set for whoever is in charge of this thread", Thread.interrupted())
    }

    @Test
    fun `an uninterrupted sleep returns what it slept and leaves the flag alone`() {
        assertEquals(10L, Utils.ms_sleep(10))
        assertTrue(!Thread.currentThread().isInterrupted)
    }
}
