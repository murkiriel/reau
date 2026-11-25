package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery's current and charge and the process's CPU use in the `device …` line: the conversions, what is not a value
 * (null, written `-`), the CPU window, and the places of the new fields in the line.
 */
class PowerReadingsTest {
    private val base = DeviceState(
        batteryPct = 57, charging = false, batteryTempC = 31.5f, batteryMv = 4123,
        thermal = 1, headroom = null,
        appUsedMb = 45, appMaxMb = 512, phoneAvailMb = 1830, lowMemory = false,
        screenOn = true, interactive = true, dozing = false, powerSave = false, batteryExempt = false,
        bikeWifi = null, data = DeviceState.Data.CELL, dataValidated = true,
    )

    @Test
    fun `microamps become milliamps and microamp-hours become milliamp-hours, with the sign the phone gives`() {
        assertEquals(-310, PowerReadings.currentMa(-310_000))
        assertEquals(1250, PowerReadings.currentMa(1_250_000))
        assertEquals(-310, PowerReadings.currentMa(-310_000L))
        assertEquals(2840, PowerReadings.chargeMah(2_840_000))
        assertEquals(2840, PowerReadings.chargeMah(2_840_000L))
    }

    @Test
    fun `the value a phone gives when it cannot say is null, for the current and for the charge`() {
        assertNull(PowerReadings.currentMa(Int.MIN_VALUE))
        assertNull(PowerReadings.currentMa(Long.MIN_VALUE))
        assertNull(PowerReadings.chargeMah(Int.MIN_VALUE))
        assertNull(PowerReadings.chargeMah(Long.MIN_VALUE))
    }

    @Test
    fun `a charge counter of zero is a phone without one, so null, but a current of zero is a real reading`() {
        assertNull(PowerReadings.chargeMah(0))
        assertNull(PowerReadings.chargeMah(0L))
        assertEquals(0, PowerReadings.currentMa(0))
    }

    @Test
    fun `the new fields are written after the power parts and before the memory, and what is null is a dash`() {
        val said = base.copy(currentMa = -310, chargeMah = 2840, cpuPct = 35).line()
        assertTrue(said, " temp=31.5C volt=4.123V thermal=light current=-310mA charge=2840mAh cpu=35% mem=45/512MB " in said)
        val none = base.line()
        assertTrue(none, " thermal=light current=- charge=- cpu=- mem=45/512MB " in none)
        for (invalid in listOf(PowerReadings.currentMa(Int.MIN_VALUE), PowerReadings.currentMa(Long.MIN_VALUE))) {
            assertTrue(base.copy(currentMa = invalid).line(), " current=- " in base.copy(currentMa = invalid).line())
        }
        assertTrue(base.copy(chargeMah = PowerReadings.chargeMah(0)).line(), " charge=- " in base.copy(chargeMah = PowerReadings.chargeMah(0)).line())
    }

    @Test
    fun `the new fields are not a reason to write a line`() {
        assertEquals(emptyList<String>(), base.relevantChangesFrom(base.copy(currentMa = -900, chargeMah = 100, cpuPct = 300)))
        assertEquals(emptyList<String>(), base.copy(currentMa = 5, chargeMah = 5, cpuPct = 5).relevantChangesFrom(base))
        val written = ArrayList<String>()
        val log = DeviceStateLog(periodMs = 30_000) { written += it }
        log.offer(0, base.copy(cpuPct = 1))
        log.offer(5_000, base.copy(currentMa = -400, chargeMah = 2000, cpuPct = 90))
        assertEquals(1, written.size)
    }

    @Test
    fun `the first look has no CPU delta`() {
        assertNull(CpuWindow().offer(cpuMs = 1_000, clockMs = 10_000))
    }

    @Test
    fun `250 ms of CPU in 5000 ms is 5 percent of one core, and more than one core passes 100`() {
        val w = CpuWindow()
        w.offer(cpuMs = 1_000, clockMs = 10_000)
        assertEquals(5, w.offer(cpuMs = 1_250, clockMs = 15_000))
        assertEquals(150, w.offer(cpuMs = 1_250 + 7_500, clockMs = 20_000))
    }

    @Test
    fun `the delta is against the look before, not the total`() {
        val w = CpuWindow()
        w.offer(cpuMs = 4_000, clockMs = 10_000)
        w.offer(cpuMs = 4_500, clockMs = 15_000)
        assertEquals(10, w.offer(cpuMs = 5_000, clockMs = 20_000))
    }

    @Test
    fun `a window of zero milliseconds or less, or a clock that went back, is null and the next look recovers`() {
        val w = CpuWindow()
        w.offer(cpuMs = 1_000, clockMs = 10_000)
        assertNull("0 ms", w.offer(cpuMs = 1_100, clockMs = 10_000))
        assertNull("clock back", w.offer(cpuMs = 1_200, clockMs = 9_000))
        assertEquals(10, w.offer(cpuMs = 1_700, clockMs = 14_000))
    }

    @Test
    fun `a CPU time that went back is null too`() {
        val w = CpuWindow()
        w.offer(cpuMs = 1_000, clockMs = 10_000)
        assertNull(w.offer(cpuMs = 500, clockMs = 15_000))
    }
}
