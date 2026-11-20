package com.reau.app.stream

import com.reau.app.dash.DashReading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reserve as the stream may trust it (D117): the low-fuel reminder `0C05`, `55` on, `AA` off, and only if it came in THIS dash session. */
class ReserveCapTest {
    private fun reading(hex: String, atMs: Long) = DashReading(hex, atMs, count = 1)

    @Test
    fun `a 55 that came in this dash session is the reserve`() {
        assertTrue(ReserveCap.lowFuel(reading("55", atMs = 10_000), sessionStartedAtMs = 5_000))
        assertTrue("a reading at the very instant of the start counts", ReserveCap.lowFuel(reading("55", atMs = 5_000), sessionStartedAtMs = 5_000))
    }

    @Test
    fun `a 55 kept from an older session says nothing`() {
        assertFalse(ReserveCap.lowFuel(reading("55", atMs = 4_999), sessionStartedAtMs = 5_000))
    }

    @Test
    fun `AA, any other value and no reading are no reserve`() {
        assertFalse(ReserveCap.lowFuel(reading("AA", atMs = 10_000), sessionStartedAtMs = 5_000))
        assertFalse(ReserveCap.lowFuel(reading("00", atMs = 10_000), sessionStartedAtMs = 5_000))
        assertFalse(ReserveCap.lowFuel(reading("5555", atMs = 10_000), sessionStartedAtMs = 5_000))
        assertFalse(ReserveCap.lowFuel(null, sessionStartedAtMs = 5_000))
    }

    @Test
    fun `the loop reads the code of the low-fuel reminder, the dash session's start and the live reading`() {
        org.junit.Assert.assertEquals("0C05", ReserveCap.CODE)
        val loop = java.io.File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("lowFuel = runCatching { ReserveCap.lowFuel(reserveReading(), sessionStartedAtMs) }.getOrDefault(false)"))
        val sides = java.io.File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(sides.contains("reserveReading = { com.reau.app.dash.DashReadings.all.value[com.reau.app.stream.ReserveCap.CODE] }"))
        // the session's start is taken when the dash is dialled and given to every loop made in that session, a new stream of the same session included
        assertTrue(sides.contains("sessionStartedAtMs = dashSessionStartedAtMs"))
        assertTrue(sides.substringAfter("override fun startDash()").substringBefore("linkUp.start(").contains("live.dashSessionStartedAtMs = com.reau.app.stream.ReserveCap.nowMs()"))
    }
}
