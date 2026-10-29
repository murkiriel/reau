package com.reau.app.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's name, which the owner chose, stays out of the log that is shared (audit of 26/10/2026). */
class DiscoveryLogTest {
    @Test
    fun `the line says the request came and how long the name is, never the name`() {
        val line = DiscoveryLog.line("Maria's Pixel")
        assertTrue(line, line.startsWith("Service Discovery Request"))
        assertTrue(line, "13 characters" in line)
        assertFalse(line, "Maria" in line || "Pixel" in line)
    }

    @Test
    fun `an empty name is not a problem`() {
        assertTrue("0 characters" in DiscoveryLog.line(""))
    }
}
