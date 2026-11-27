package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision of "Tela acesa com o celular bloqueado": when the screen is lit and when it is not, the display's edge and the lines of the log. Pure, no Android. */
class ScreenKeepPolicyTest {
    private fun why(enabled: Boolean = true, since: Long? = null, paused: Boolean = false) = ScreenKeepPolicy.why(enabled, since, paused)

    @Test
    fun `the screen going off lights it, with no earlier light, in any place`() {
        assertNull(why())
    }

    @Test
    fun `with the switch off nothing is ever lit`() {
        assertEquals("off", why(enabled = false))
        assertEquals("the switch off comes before the pause", "off", why(enabled = false, paused = true))
    }

    @Test
    fun `the rider's pause lights nothing, and without it the same call lights`() {
        assertEquals("paused", why(paused = true))
        assertNull(why(paused = false))
    }

    @Test
    fun `nothing is lit again within the minimum gap, so the screen cannot loop`() {
        assertEquals("gap", why(since = 0L))
        assertEquals("gap", why(since = ScreenKeepPolicy.MIN_GAP_MS - 1))
        assertNull(why(since = ScreenKeepPolicy.MIN_GAP_MS))
        assertNull(why(since = 60_000L))
        assertEquals("a pause is said before the gap", "paused", why(since = 1_000L, paused = true))
        assertNull("a clock that went back lights", why(since = -5L))
    }

    @Test
    fun `the lines of the log are short`() {
        assertEquals("on mode=off lockui=on", ScreenKeepPolicy.ON_LINE)
        assertEquals("wake reason=screenoff after=0s", ScreenKeepPolicy.wakeLine(0))
        assertEquals("wake reason=screenoff after=61s", ScreenKeepPolicy.wakeLine(61))
        assertEquals("wake reason=start after=0s", ScreenKeepPolicy.wakeLine(0, ScreenKeepPolicy.REASON_START))
        assertEquals("wake reason=dark after=7s", ScreenKeepPolicy.wakeLine(7, ScreenKeepPolicy.REASON_DARK))
        assertEquals("off", ScreenKeepPolicy.OFF_LINE)
        assertEquals("screen-keep", ScreenKeepPolicy.TAG)
        assertEquals(61L, ScreenKeepPolicy.secondsOff(offSinceMs = 1_000L, nowMs = 62_999L))
        assertEquals("a clock that went back is zero", 0L, ScreenKeepPolicy.secondsOff(offSinceMs = 5_000L, nowMs = 1_000L))
        assertEquals("nothing trigger=screenoff reason=gap", ScreenKeepPolicy.nothingLine("gap"))
        assertEquals("nothing trigger=screenoff reason=paused", ScreenKeepPolicy.nothingLine(ScreenKeepPolicy.WHY_PAUSED))
    }

    @Test
    fun `a light that did not light the screen is asked again by the tick, after the gap, a few times and only while the screen is still off`() {
        fun again(off: Boolean = true, asked: Boolean = true, since: Long? = 6_000L, tries: Int = 0) = ScreenKeepPolicy.darkRetry(off, asked, since, tries)
        assertTrue(again())
        assertTrue("exactly at the gap", again(since = ScreenKeepPolicy.DARK_AFTER_MS))
        assertFalse("too soon: the gap is respected", again(since = ScreenKeepPolicy.DARK_AFTER_MS - 1))
        assertEquals("the same time as the gap that holds a light back", ScreenKeepPolicy.MIN_GAP_MS, ScreenKeepPolicy.DARK_AFTER_MS)
        assertFalse("the screen is on", again(off = false))
        assertFalse("no light was asked, or the screen was seen on since", again(asked = false))
        assertFalse("no light yet", again(since = null))
        assertTrue(again(tries = ScreenKeepPolicy.MAX_DARK_TRIES - 1))
        assertFalse("not asked for ever", again(tries = ScreenKeepPolicy.MAX_DARK_TRIES))
        assertEquals(3, ScreenKeepPolicy.MAX_DARK_TRIES)
    }

    @Test
    fun `a moment after a light the log says when the app's activity was not in front, or had no lock screen to show over`() {
        assertEquals("lockui not-front", ScreenKeepPolicy.frontLine(activityResumed = false, keyguardLocked = true))
        assertEquals("not resumed counts first, locked or not", "lockui not-front", ScreenKeepPolicy.frontLine(activityResumed = false, keyguardLocked = false))
        assertEquals("lockui no-keyguard", ScreenKeepPolicy.frontLine(activityResumed = true, keyguardLocked = false))
        assertNull("in front over the lock screen: the activity says shown itself", ScreenKeepPolicy.frontLine(activityResumed = true, keyguardLocked = true))
        assertEquals("lockui armed", ScreenKeepPolicy.ARMED_LINE)
        assertEquals("lockui shown", ScreenKeepPolicy.SHOWN_LINE)
    }

    private fun ScreenKeepPolicy.DisplayEdge.line(off: Boolean, via: ScreenKeepPolicy.Via, nowMs: Long = 0L): String? = heard(off, via, nowMs)!!.line

    @Test
    fun `a change of the screen is said once, from whoever sees it first, with the line of the log`() {
        val edge = ScreenKeepPolicy.DisplayEdge()
        assertNull("still on", edge.heard(false, ScreenKeepPolicy.Via.TICK, 0L))
        assertEquals("display off via=listener", edge.line(true, ScreenKeepPolicy.Via.LISTENER))
        assertNull("the broadcast says it again", edge.heard(true, ScreenKeepPolicy.Via.BROADCAST, 100L))
        assertNull("so does the tick", edge.heard(true, ScreenKeepPolicy.Via.TICK, 200L))
        assertEquals("a change inside the gap is taken but its line is grouped", null, edge.line(false, ScreenKeepPolicy.Via.BROADCAST, 300L))
        assertFalse("and it is taken: the screen is on", edge.off)
        assertEquals("the line after the gap says the count of changes so far", "display off via=tick n=3", edge.line(true, ScreenKeepPolicy.Via.TICK, 2_300L))
        edge.reset(false)
        assertNull("after a reset the same state says nothing", edge.heard(false, ScreenKeepPolicy.Via.LISTENER, 5_000L))
        assertEquals("a reset begins the count again", "display off via=listener", edge.line(true, ScreenKeepPolicy.Via.LISTENER, 5_100L))
        assertEquals(listOf("listener", "broadcast", "tick"), ScreenKeepPolicy.Via.entries.map { it.logName })
        assertEquals(5_000L, ScreenKeepPolicy.TICK_MS)
    }

    @Test
    fun `a sensor that flaps in a pocket says one display line in each window, with the count`() {
        val edge = ScreenKeepPolicy.DisplayEdge()
        val said = (0 until 20).mapNotNull { n -> edge.heard(n % 2 == 0, ScreenKeepPolicy.Via.LISTENER, n * 100L)!!.line }
        assertEquals("20 changes in 2 s: one line, the first, at once", listOf("display off via=listener"), said)
        assertEquals("the next window's line has the total", "display off via=listener n=21", edge.line(true, ScreenKeepPolicy.Via.LISTENER, 2_000L))
    }

    @Test
    fun `only a display that is on is the screen on, off and doze and the suspended ones are the screen gone`() {
        assertFalse(ScreenKeepPolicy.displayOff(2))
        for (state in listOf(1, 3, 4, 5, 6, 0)) assertTrue("state $state", ScreenKeepPolicy.displayOff(state))
    }

    private fun covers(held: Boolean = true, interactive: Boolean = true, near: Boolean? = true, quietMs: Long = 60_000L) =
        ScreenKeepPolicy.proximityCovers(held, interactive, near, quietMs)

    @Test
    fun `a display off with the proximity lock held, the phone interactive and the sensor covered is the lock's own, and lights nothing`() {
        assertTrue(covers())
        assertEquals("proximity", ScreenKeepPolicy.why(enabled = true, sinceLastWakeMs = null, pausedByRider = false, proximity = true))
        assertEquals("nothing trigger=screenoff reason=proximity", ScreenKeepPolicy.nothingLine(ScreenKeepPolicy.WHY_PROXIMITY))
        assertNull("the same call without it lights", ScreenKeepPolicy.why(enabled = true, sinceLastWakeMs = null))
        assertEquals("the switch off and the rider's pause come first", "off", ScreenKeepPolicy.why(false, null, pausedByRider = false, proximity = true))
        assertEquals("paused", ScreenKeepPolicy.why(true, null, pausedByRider = true, proximity = true))
        assertEquals("the lock's off is not a gap: no retry is asked for", "proximity", ScreenKeepPolicy.why(true, 0L, proximity = true))
    }

    @Test
    fun `a display off that is not the lock's is lit as before, with the lock not held or the phone not interactive`() {
        assertFalse("the lock is not held: the old rule", covers(held = false))
        assertFalse("the phone went to sleep (the power button): a real off", covers(interactive = false))
        assertFalse(covers(held = false, interactive = false))
    }

    @Test
    fun `the sensor tells a real off from the lock's during a projection, where the phone stays interactive`() {
        assertTrue("covered", covers(near = true, quietMs = 0L))
        assertTrue("covered for an hour", covers(near = true, quietMs = 3_600_000L))
        assertFalse("a sensor not known (none, or no reading yet) is the lock's only for the grace: after it a real off is not taken for the lock for ever", covers(near = null, quietMs = ScreenKeepPolicy.PROXIMITY_GRACE_MS))
        assertFalse(covers(near = null, quietMs = 60_000L))
        assertTrue("an unknown sensor inside the grace: its first reading may be late", covers(near = null, quietMs = ScreenKeepPolicy.PROXIMITY_GRACE_MS - 1))
        assertTrue(covers(near = null, quietMs = 0L))
        assertFalse("uncovered for a while and the display still off: not the lock", covers(near = false, quietMs = ScreenKeepPolicy.PROXIMITY_GRACE_MS))
        assertTrue("uncovered but the display or the sensor changed a moment ago: the other road may be late", covers(near = false, quietMs = ScreenKeepPolicy.PROXIMITY_GRACE_MS - 1))
        assertTrue(covers(near = false, quietMs = 0L))
        assertEquals(1_500L, ScreenKeepPolicy.PROXIMITY_GRACE_MS)
    }

    @Test
    fun `the sensor's reading is one word for the log`() {
        assertEquals("near", ScreenKeepPolicy.proximityNearText(true))
        assertEquals("far", ScreenKeepPolicy.proximityNearText(false))
        assertEquals("unknown", ScreenKeepPolicy.proximityNearText(null))
    }
}
