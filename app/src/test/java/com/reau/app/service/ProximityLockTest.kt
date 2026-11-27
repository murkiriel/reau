package com.reau.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state REau's proximity lock shares between the activity, the keeper and the lock mode (0.3.99). Pure, no Android. */
class ProximityLockTest {
    @After
    fun reset() = ProximityLock.letGo()

    @Test
    fun `it is not held until the lock is taken, and holding forgets the sensor`() {
        assertFalse(ProximityLock.held.value)
        ProximityLock.hold(1_000L)
        assertTrue(ProximityLock.held.value)
        assertNull("nothing is known of the sensor yet", ProximityLock.near)
        ProximityLock.sensor(true, 1_100L)
        ProximityLock.hold(2_000L)
        assertNull("a new hold begins from nothing", ProximityLock.near)
        ProximityLock.letGo()
        assertFalse(ProximityLock.held.value)
        assertNull(ProximityLock.near)
    }

    @Test
    fun `the sensor says a change once, and under the range is covered`() {
        ProximityLock.hold(0L)
        assertTrue(ProximityLock.sensor(true, 100L))
        assertFalse("the same reading is not a change", ProximityLock.sensor(true, 200L))
        assertTrue(ProximityLock.sensor(false, 300L))
        assertEquals(false, ProximityLock.near)
        assertTrue(ProximityLock.isNear(0f, 5f))
        assertFalse(ProximityLock.isNear(5f, 5f))
        assertFalse(ProximityLock.isNear(8f, 8f))
        assertTrue(ProximityLock.isNear(3f, 8f))
    }

    @Test
    fun `a reading is covered under the smaller of the range and 5 cm, as the system's own controller reads it`() {
        assertEquals(5f, ProximityLock.NEAR_CM)
        for (range in listOf(8f, 10f)) {
            assertFalse("range $range: 5 cm is far", ProximityLock.isNear(5f, range))
            assertTrue("range $range: 0 is near", ProximityLock.isNear(0f, range))
            assertTrue("range $range: just under 5 is near", ProximityLock.isNear(4.9f, range))
            assertFalse("range $range: the range itself is far", ProximityLock.isNear(range, range))
        }
        assertTrue("a binary sensor of range 1: 0 is near", ProximityLock.isNear(0f, 1f))
        assertTrue(ProximityLock.isNear(0.5f, 1f))
        assertFalse("a binary sensor of range 1: 1 is far", ProximityLock.isNear(1f, 1f))
        assertFalse("a negative value is no reading", ProximityLock.isNear(-1f, 5f))
    }

    @Test
    fun `it covers a display that is off only with the lock held, and the quiet time is the shorter of the display's and the sensor's`() {
        assertFalse("not held", ProximityLock.covers(interactive = true, displayOffSinceMs = 0L, nowMs = 60_000L))
        ProximityLock.hold(0L)
        assertFalse("held, interactive, sensor unknown for good: not the lock", ProximityLock.covers(interactive = true, displayOffSinceMs = 0L, nowMs = 60_000L))
        assertTrue("sensor unknown, inside the grace of the lock being taken", ProximityLock.covers(interactive = true, displayOffSinceMs = 0L, nowMs = 1_000L))
        assertFalse("sensor unknown, after the grace", ProximityLock.covers(interactive = true, displayOffSinceMs = 0L, nowMs = ScreenKeepPolicy.PROXIMITY_GRACE_MS))
        assertFalse("not interactive: a real off", ProximityLock.covers(interactive = false, displayOffSinceMs = 0L, nowMs = 60_000L))
        ProximityLock.sensor(true, 1_000L)
        assertTrue("covered", ProximityLock.covers(interactive = true, displayOffSinceMs = 2_000L, nowMs = 60_000L))
        ProximityLock.sensor(false, 50_000L)
        assertTrue("uncovered a moment ago: given the grace", ProximityLock.covers(interactive = true, displayOffSinceMs = 2_000L, nowMs = 50_500L))
        assertFalse("uncovered a while ago and the display still off: not the lock", ProximityLock.covers(interactive = true, displayOffSinceMs = 2_000L, nowMs = 60_000L))
        assertTrue("the display went off a moment ago: the sensor's event may be late", ProximityLock.covers(interactive = true, displayOffSinceMs = 59_900L, nowMs = 60_000L))
        assertTrue("a clock that went back does not break it", ProximityLock.covers(interactive = true, displayOffSinceMs = 70_000L, nowMs = 60_000L))
    }
}
