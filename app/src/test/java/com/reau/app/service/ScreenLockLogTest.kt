package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock mode's own lines of the 0.3.99 log ([ScreenLockLog]): the brightness, the grouping of touches and Back, the drags, the focus, the lifecycle and the summary. */
class ScreenLockLogTest {
    @Test
    fun `the brightness is said at each change with its reason, and the way in is the first`() {
        val track = ScreenLockLog.DimTrack()
        assertEquals("brightness bright reason=enter", track.set(false, ScreenLockLog.BRIGHT_ENTER, 0L))
        assertNull("bright again says nothing", track.set(false, ScreenLockLog.BRIGHT_TOUCH, 1_000L))
        assertEquals("brightness dim reason=idle", track.set(true, null, 5_000L))
        assertNull("dim again says nothing", track.set(true, null, 6_000L))
        assertEquals("brightness bright reason=touch", track.set(false, ScreenLockLog.BRIGHT_TOUCH, 20_000L))
        assertEquals("brightness dim reason=idle", track.set(true, null, 25_000L))
        assertEquals("brightness bright reason=drag", track.set(false, ScreenLockLog.BRIGHT_DRAG, 30_000L))
        assertEquals("brightness dim reason=idle", track.set(true, null, 35_000L))
        assertEquals("coming back with no reason is the exit", "brightness bright reason=exit", track.set(false, null, 40_000L))
    }

    @Test
    fun `the time dim is the sum of the stretches, the open one included`() {
        val track = ScreenLockLog.DimTrack()
        track.set(false, ScreenLockLog.BRIGHT_ENTER, 0L)
        track.set(true, null, 5_000L)
        track.set(false, ScreenLockLog.BRIGHT_TOUCH, 15_000L)
        assertEquals(10_000L, track.dimMs(16_000L))
        track.set(true, null, 20_000L)
        assertEquals("the open stretch counts up to now", 14_000L, track.dimMs(24_000L))
        assertEquals("a clock that went back is not negative", 10_000L, track.dimMs(19_000L))
    }

    @Test
    fun `the screen over the lock screen has its own label`() {
        assertEquals("lockui brightness dim reason=idle", ScreenLockLog.DimTrack("lockui brightness").set(true, null, 0L))
    }

    @Test
    fun `a line is due at once, then not before the gap, and says the count so far`() {
        val group = ScreenLockLog.Grouper()
        assertEquals(1L, group.hit(0L))
        assertNull(group.hit(500L))
        assertNull(group.hit(1_999L))
        assertEquals("the ones left out are in the count", 4L, group.hit(2_000L))
        assertNull(group.hit(2_500L))
        assertEquals(6L, group.hit(5_000L))
        assertEquals(6L, group.total)
    }

    @Test
    fun `a clock that went back says the line rather than hide it`() {
        val group = ScreenLockLog.Grouper()
        assertEquals(1L, group.hit(10_000L))
        assertEquals(2L, group.hit(5_000L))
    }

    @Test
    fun `the touch, the abort and the done lines`() {
        assertEquals("slide touch n=7", ScreenLockLog.touchLine(7))
        assertEquals("slide aborted at 47%", ScreenLockLog.abortLine(ScreenLockLog.percent(0.47f)))
        assertEquals(0, ScreenLockLog.percent(-1f))
        assertEquals(100, ScreenLockLog.percent(2f))
        assertEquals("slide done after 1.4s of drag", ScreenLockLog.doneLine(1_450L))
        assertEquals("slide done after 0.0s of drag", ScreenLockLog.doneLine(-5L))
        assertEquals("slide done after 12.9s of drag", ScreenLockLog.doneLine(12_999L))
    }

    @Test
    fun `back is said plainly the first time and with a count after`() {
        assertEquals("back ignored", ScreenLockLog.backLine(1))
        assertEquals("back ignored n=5", ScreenLockLog.backLine(5))
    }

    @Test
    fun `the focus lost is said once, and when it is back it says how long it was away`() {
        val counters = ScreenLockLog.Counters(0L, 0L, 0L)
        assertNull("gained while not lost", counters.focus(true, 100L))
        assertEquals("focus lost", counters.focus(false, 1_000L))
        assertNull("lost again before it came back", counters.focus(false, 2_000L))
        assertEquals("focus regained after=4s", counters.focus(true, 5_000L))
        assertEquals("focus lost", counters.focus(false, 9_000L))
        assertEquals("two losses", 2L, counters.focusLost)
    }

    @Test
    fun `the lifecycle lines say how long the activity was away when it is back`() {
        val counters = ScreenLockLog.Counters(0L, 0L, 0L)
        assertEquals("activity paused", counters.activity(ScreenLockLog.PAUSED, 1_000L))
        assertEquals("activity stopped", counters.activity(ScreenLockLog.STOPPED, 1_100L))
        assertEquals("activity resumed after=9s", counters.activity(ScreenLockLog.RESUMED, 10_000L))
        assertEquals("a resume with no pause before it has no time", "activity resumed", counters.activity(ScreenLockLog.RESUMED, 11_000L))
    }

    @Test
    fun `the sensor lines carry the raw value and the range`() {
        assertEquals("sensor proximity=near value=0.0", ScreenLockLog.sensorLine(true, 0f))
        assertEquals("sensor proximity=far value=5.0", ScreenLockLog.sensorLine(false, 5f))
        assertEquals("sensor proximity registered max=5.0", ScreenLockLog.sensorRegisteredLine(5f))
    }

    @Test
    fun `the time off by the proximity lock is counted once per stretch, and a stretch still open at the end counts`() {
        val counters = ScreenLockLog.Counters(0L, 0L, 0L)
        counters.proximityOff(1_000L)
        counters.proximityOff(2_000L)
        counters.proximityOn(4_000L)
        counters.proximityOn(5_000L)
        counters.proximityOff(10_000L)
        val summary = counters.summary(16_000L, 0L, 0L, 0L, 0L)
        assertEquals("3 s and then 6 s still open", 9_000L, summary.proximityOffMs)
    }

    @Test
    fun `the summary has the numbers of the mode, the keeper's and the lock screen's as differences from where they began`() {
        val counters = ScreenLockLog.Counters(startMs = 1_000L, wakesAtStart = 4L, locksAtStart = 1L)
        repeat(7) { counters.touches.hit(it * 3_000L) }
        counters.abort()
        counters.abort()
        counters.focus(false, 5_000L)
        val summary = counters.summary(nowMs = 63_000L, dimMs = 41_500L, backs = 3L, wakes = 5L, keyguardLocks = 1L)
        assertEquals(
            "summary after=62s dim=41s proximity-off=0s wakes=1 slide-touches=7 slide-aborted=2 back-ignored=3 keyguard-locks=0 focus-lost=1",
            summary.line(),
        )
        assertEquals("a lock screen that came up in the mode", "keyguard-locks=2", Regex("""keyguard-locks=\d+""").find(counters.summary(63_000L, 0L, 0L, 5L, 3L).line())!!.value)
    }

    @Test
    fun `the lines sit under the lock tag, and the group is two seconds`() {
        assertEquals(2_000L, ScreenLockLog.GROUP_MS)
        assertFalse(ScreenLockLog.touchLine(1).startsWith("W: "))
        assertTrue(ScreenLock.TAG == "lock")
    }
}
