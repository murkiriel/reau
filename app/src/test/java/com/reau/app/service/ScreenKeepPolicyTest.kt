package com.reau.app.service

import com.reau.app.service.ScreenKeepPolicy.Decision
import com.reau.app.service.ScreenKeepPolicy.Trigger
import com.reau.app.settings.ScreenKeepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision of "Tela acesa com o celular bloqueado": when the screen is lit, when it is not, and when the next try is. Pure, no Android. */
class ScreenKeepPolicyTest {
    private fun decide(
        mode: ScreenKeepMode = ScreenKeepMode.ON_SCREEN_OFF,
        trigger: Trigger = Trigger.SCREEN_OFF,
        enabled: Boolean = true,
        screenOn: Boolean = false,
        covered: Boolean? = false,
        since: Long? = null,
    ) = ScreenKeepPolicy.decide(enabled, mode, trigger, screenOn, covered, since)

    @Test
    fun `the screen going off lights it in the mode that lights at every screen-off`() {
        assertEquals(Decision.WAKE, decide())
    }

    @Test
    fun `with the switch off nothing is ever lit`() {
        for (mode in ScreenKeepMode.entries) for (trigger in Trigger.entries) {
            assertEquals("$mode $trigger", Decision.NOTHING, decide(mode = mode, trigger = trigger, enabled = false))
        }
    }

    @Test
    fun `the modes with an interval do not light at the screen-off itself, only their timer does`() {
        for (mode in listOf(ScreenKeepMode.EVERY_60_S, ScreenKeepMode.EVERY_30_S)) {
            assertEquals(mode.toString(), Decision.NOTHING, decide(mode = mode, trigger = Trigger.SCREEN_OFF))
            assertEquals(mode.toString(), Decision.WAKE, decide(mode = mode, trigger = Trigger.TIMER))
        }
    }

    @Test
    fun `the mode of every screen-off has no timer`() {
        assertEquals(Decision.NOTHING, decide(mode = ScreenKeepMode.ON_SCREEN_OFF, trigger = Trigger.TIMER))
    }

    @Test
    fun `a timer that finds the screen on lights nothing`() {
        assertEquals(Decision.NOTHING, decide(mode = ScreenKeepMode.EVERY_30_S, trigger = Trigger.TIMER, screenOn = true))
    }

    @Test
    fun `a covered proximity sensor skips the light and a free one or none lights`() {
        assertEquals(Decision.SKIP_PROXIMITY, decide(covered = true))
        assertEquals(Decision.WAKE, decide(covered = false))
        assertEquals("no sensor, or no answer in time", Decision.WAKE, decide(covered = null))
        assertEquals(Decision.SKIP_PROXIMITY, decide(mode = ScreenKeepMode.EVERY_60_S, trigger = Trigger.TIMER, covered = true))
    }

    @Test
    fun `nothing is lit again within the minimum gap, so the screen cannot loop`() {
        assertEquals(Decision.NOTHING, decide(since = 0L))
        assertEquals(Decision.NOTHING, decide(since = ScreenKeepPolicy.MIN_GAP_MS - 1))
        assertEquals(Decision.WAKE, decide(since = ScreenKeepPolicy.MIN_GAP_MS))
        assertEquals(Decision.WAKE, decide(since = 60_000L))
        assertEquals("a covered sensor inside the gap is still nothing", Decision.NOTHING, decide(covered = true, since = 1_000L))
    }

    @Test
    fun `the next light of a timer is at the next multiple of the interval since the screen went off`() {
        val sixty = ScreenKeepMode.EVERY_60_S
        assertEquals(60_000L, ScreenKeepPolicy.nextDelayMs(sixty, offSinceMs = 1_000L, nowMs = 1_000L))
        assertEquals(40_000L, ScreenKeepPolicy.nextDelayMs(sixty, offSinceMs = 1_000L, nowMs = 21_000L))
        assertEquals("exactly on a multiple waits the next one", 60_000L, ScreenKeepPolicy.nextDelayMs(sixty, offSinceMs = 0L, nowMs = 60_000L))
        assertEquals("a skip does not move the others", 59_000L, ScreenKeepPolicy.nextDelayMs(sixty, offSinceMs = 0L, nowMs = 61_000L))
        val thirty = ScreenKeepMode.EVERY_30_S
        assertEquals(1L, ScreenKeepPolicy.nextDelayMs(thirty, offSinceMs = 0L, nowMs = 29_999L))
        assertEquals(30_000L, ScreenKeepPolicy.nextDelayMs(thirty, offSinceMs = 5_000L, nowMs = 4_000L))
    }

    @Test
    fun `the mode with no interval has no next light`() {
        assertNull(ScreenKeepPolicy.nextDelayMs(ScreenKeepMode.ON_SCREEN_OFF, 0L, 10_000L))
    }

    @Test
    fun `a covered reading is near and under the sensor's range, whatever kind of sensor it is`() {
        assertTrue("binary sensor, near", ScreenKeepPolicy.covered(0f, 5f))
        assertFalse("binary sensor, far", ScreenKeepPolicy.covered(5f, 5f))
        assertTrue("a sensor of centimetres, a hand", ScreenKeepPolicy.covered(2f, 10f))
        assertFalse("a sensor of centimetres, far", ScreenKeepPolicy.covered(8f, 10f))
        assertFalse("a sensor of 1 cm range, far", ScreenKeepPolicy.covered(1f, 1f))
        assertTrue("a sensor of 1 cm range, near", ScreenKeepPolicy.covered(0f, 1f))
    }

    @Test
    fun `the ride log lines are short and tell the mode, the reason and the seconds`() {
        assertEquals("on mode=off lockui=off", ScreenKeepPolicy.onLine(ScreenKeepMode.ON_SCREEN_OFF, false))
        assertEquals("on mode=n60 lockui=on", ScreenKeepPolicy.onLine(ScreenKeepMode.EVERY_60_S, true))
        assertEquals("on mode=n30 lockui=off", ScreenKeepPolicy.onLine(ScreenKeepMode.EVERY_30_S, false))
        assertEquals("wake reason=screenoff after=0s", ScreenKeepPolicy.wakeLine(Trigger.SCREEN_OFF, 0))
        assertEquals("wake reason=timer after=60s", ScreenKeepPolicy.wakeLine(Trigger.TIMER, 60))
        assertEquals("skip proximity", ScreenKeepPolicy.SKIP_LINE)
        assertEquals("off", ScreenKeepPolicy.OFF_LINE)
        assertEquals("screen-keep", ScreenKeepPolicy.TAG)
        assertEquals(61L, ScreenKeepPolicy.secondsOff(offSinceMs = 1_000L, nowMs = 62_999L))
        assertEquals("a clock that went back is zero", 0L, ScreenKeepPolicy.secondsOff(offSinceMs = 5_000L, nowMs = 1_000L))
    }
}
