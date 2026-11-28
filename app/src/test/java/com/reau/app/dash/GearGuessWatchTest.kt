package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ride log's line for the dash's `09 08` (decision of 22/11/2026: only a line, to confirm with more rides; nothing changes in what REau does):
 * "in gear?" for `AA`, "neutral or clutch?" for `55`, written only when the value changes.
 */
class GearGuessWatchTest {
    private val watch = GearGuessWatch()

    @Test
    fun `the first reading of a connection is written, with the guess and the doubt`() {
        assertEquals("09 08: AA (in gear?) (first reading of the connection)", watch.onReading(0xAA, 1_000L, null))
        assertEquals("09 08: 55 (neutral or clutch?) (first reading of the connection)", GearGuessWatch().onReading(0x55, 1_000L, null))
    }

    @Test
    fun `a value that is neither AA nor 55 is written raw and unknown`() {
        assertEquals("09 08: 0x3C (unknown) (first reading of the connection)", watch.onReading(0x3C, 0L, null))
        assertEquals("09 08: 0x00 (unknown) after 0x3C (unknown) for 1.0 s", watch.onReading(0x00, 1_000L, null))
    }

    @Test
    fun `the same value again says nothing, as often as it comes`() {
        watch.onReading(0x55, 0L, null)
        for (t in 1..50) assertNull(watch.onReading(0x55, t * 1_000L, 0.0))
    }

    @Test
    fun `a change says what it was and for how long`() {
        watch.onReading(0x55, 10_000L, null)
        assertEquals("09 08: AA (in gear?) after 55 (neutral or clutch?) for 42.1 s", watch.onReading(0xAA, 52_100L, null))
        assertEquals("09 08: 55 (neutral or clutch?) after AA (in gear?) for 3.0 s", watch.onReading(0x55, 55_100L, null))
    }

    @Test
    fun `the speed from the navigation goes at the end when there is one, whole km per hour`() {
        watch.onReading(0x55, 0L, null)
        assertEquals("09 08: AA (in gear?) after 55 (neutral or clutch?) for 29.0 s, nav speed 63 km/h", watch.onReading(0xAA, 29_000L, 62.6))
        assertEquals("09 08: 55 (neutral or clutch?) after AA (in gear?) for 1.0 s, nav speed 0 km/h", watch.onReading(0x55, 30_000L, 0.0))
    }

    @Test
    fun `the time counts from the change, not from the readings that repeated the value`() {
        watch.onReading(0x55, 0L, null)
        watch.onReading(0x55, 20_000L, null)
        assertEquals("09 08: AA (in gear?) after 55 (neutral or clutch?) for 30.0 s", watch.onReading(0xAA, 30_000L, null))
    }

    @Test
    fun `a new connection writes its first reading again, even the same value`() {
        watch.onReading(0xAA, 0L, null)
        assertNull(watch.onReading(0xAA, 1_000L, null))
        watch.reset()
        assertEquals("09 08: AA (in gear?) (first reading of the connection)", watch.onReading(0xAA, 2_000L, null))
    }

    @Test
    fun `the two guesses are named as guesses`() {
        assertEquals(0xAA, GearGuessWatch.GEAR_GUESS_ON)
        assertEquals(0x55, GearGuessWatch.GEAR_GUESS_OFF)
    }
}
