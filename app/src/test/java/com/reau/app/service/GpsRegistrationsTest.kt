package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A location listener left registered kept the GPS on at 1 Hz until the process died (audit of 26/10/2026). */
class GpsRegistrationsTest {
    private class Fake(val failFixes: Boolean = false, val failSatellites: Boolean = false, val failRemove: Boolean = false) : GpsRegistration {
        val calls = mutableListOf<String>()
        override fun requestFixes() { calls += "fixes"; if (failFixes) error("no GPS provider") }
        override fun registerSatellites() { calls += "satellites"; if (failSatellites) error("no GNSS") }
        override fun removeFixes() { calls += "remove"; if (failRemove) error("already gone") }
    }

    @Test
    fun `both taken is started and nothing is given back`() {
        val f = Fake()
        assertTrue(GpsRegistrations.start(f))
        assertEquals(listOf("fixes", "satellites"), f.calls)
    }

    @Test
    fun `the satellites refused after the fixes were taken gives the fixes back`() {
        val f = Fake(failSatellites = true)
        assertFalse(GpsRegistrations.start(f))
        assertEquals(listOf("fixes", "satellites", "remove"), f.calls)
    }

    @Test
    fun `the fixes refused ends there, and a removal that fails too is not a crash`() {
        val f = Fake(failFixes = true, failRemove = true)
        assertFalse(GpsRegistrations.start(f))
        assertEquals(listOf("fixes", "remove"), f.calls)
    }
}
