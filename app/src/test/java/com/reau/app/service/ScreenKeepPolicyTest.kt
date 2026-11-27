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

    @Test
    fun `a change of the screen is said once, from whoever sees it first, with the line of the log`() {
        val edge = ScreenKeepPolicy.DisplayEdge()
        assertNull("still on", edge.heard(false, ScreenKeepPolicy.Via.TICK))
        assertEquals("display off via=listener", edge.heard(true, ScreenKeepPolicy.Via.LISTENER))
        assertNull("the broadcast says it again", edge.heard(true, ScreenKeepPolicy.Via.BROADCAST))
        assertNull("so does the tick", edge.heard(true, ScreenKeepPolicy.Via.TICK))
        assertEquals("display on via=broadcast", edge.heard(false, ScreenKeepPolicy.Via.BROADCAST))
        assertEquals("display off via=tick", edge.heard(true, ScreenKeepPolicy.Via.TICK))
        edge.reset(false)
        assertNull("after a reset the same state says nothing", edge.heard(false, ScreenKeepPolicy.Via.LISTENER))
        assertEquals(listOf("listener", "broadcast", "tick"), ScreenKeepPolicy.Via.entries.map { it.logName })
        assertEquals(5_000L, ScreenKeepPolicy.TICK_MS)
    }

    @Test
    fun `only a display that is on is the screen on, off and doze and the suspended ones are the screen gone`() {
        assertFalse(ScreenKeepPolicy.displayOff(2))
        for (state in listOf(1, 3, 4, 5, 6, 0)) assertTrue("state $state", ScreenKeepPolicy.displayOff(state))
    }
}
