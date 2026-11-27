package com.reau.app.service

import com.reau.app.service.ScreenLock.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the "Bloquear a tela" button: the drag of the thumb, the reasons to leave, the display's state and the lines of the ride log. Pure, no Android. */
class ScreenLockTest {
    @Test
    fun `the button is shown only with the switch on, the projection running and the dash ready`() {
        assertTrue(ScreenLock.buttonShown(true, Phase.RUNNING, DashLink.READY))
        assertFalse("the switch off, the dash ready", ScreenLock.buttonShown(false, Phase.RUNNING, DashLink.READY))
        for (dash in DashLink.entries.filter { it != DashLink.READY }) assertFalse("the dash $dash", ScreenLock.buttonShown(true, Phase.RUNNING, dash))
        for (phase in listOf(Phase.STOPPED, Phase.PREPARING)) assertFalse("the phase $phase", ScreenLock.buttonShown(true, phase, DashLink.READY))
    }

    @Test
    fun `the mode can begin with the projection on and not otherwise`() {
        assertTrue(ScreenLock.available(projectionOn = true))
        assertFalse(ScreenLock.available(projectionOn = false))
    }

    @Test
    fun `the progress is the share of the way, held between the start and the end`() {
        assertEquals(0f, ScreenLock.progress(0f, 400f), 1e-6f)
        assertEquals(0.25f, ScreenLock.progress(100f, 400f), 1e-6f)
        assertEquals(1f, ScreenLock.progress(400f, 400f), 1e-6f)
        assertEquals("past the end", 1f, ScreenLock.progress(900f, 400f), 1e-6f)
        assertEquals("dragged to the left of the start", 0f, ScreenLock.progress(-50f, 400f), 1e-6f)
        assertEquals("a track with no way", 0f, ScreenLock.progress(100f, 0f), 1e-6f)
    }

    @Test
    fun `letting go before 90 percent sends the thumb back, at 90 percent or more leaves`() {
        assertFalse(ScreenLock.unlocked(ScreenLock.progress(0f, 400f)))
        assertFalse(ScreenLock.unlocked(ScreenLock.progress(200f, 400f)))
        assertFalse("just short", ScreenLock.unlocked(ScreenLock.progress(359f, 400f)))
        assertTrue("at 90 percent", ScreenLock.unlocked(ScreenLock.progress(360f, 400f)))
        assertTrue(ScreenLock.unlocked(ScreenLock.progress(400f, 400f)))
        assertEquals(0.9f, ScreenLock.UNLOCK_AT, 0f)
    }

    @Test
    fun `only a touch on the thumb drags it, one beside it or elsewhere drags nothing`() {
        // The thumb: a circle of 28 px around (32, 32).
        assertTrue("its centre", ScreenLock.hitsThumb(32f, 32f, 32f, 32f, 28f))
        assertTrue("its edge", ScreenLock.hitsThumb(60f, 32f, 32f, 32f, 28f))
        assertFalse("just outside", ScreenLock.hitsThumb(61f, 32f, 32f, 32f, 28f))
        assertTrue("a little slop", ScreenLock.hitsThumb(66f, 32f, 32f, 32f, 28f, slopPx = 8f))
        assertFalse("the middle of the track", ScreenLock.hitsThumb(500f, 32f, 32f, 32f, 28f, slopPx = 8f))
        assertFalse("the corner of the circle's square", ScreenLock.hitsThumb(58f, 58f, 32f, 32f, 28f))
    }

    @Test
    fun `going in says manual on once and going out says why and how long, once`() {
        val state = ScreenLock.State()
        assertFalse(state.active)
        assertNull("nothing to leave", state.exit(Exit.SWIPE, 5_000L))
        assertEquals("manual on reason=button", state.enter(10_000L))
        assertNull("already in", state.enter(11_000L))
        assertEquals("manual off reason=swipe after=62s", state.exit(Exit.SWIPE, 72_900L))
        assertNull("already out", state.exit(Exit.LEFT, 80_000L))
    }

    @Test
    fun `every reason has its word and the mode can go in again after each`() {
        assertEquals(listOf("swipe", "left", "screenoff", "projection"), Exit.entries.map { it.logName })
        val state = ScreenLock.State()
        for (reason in Exit.entries) {
            assertEquals("manual on reason=button", state.enter(0L))
            assertEquals("manual off reason=${reason.logName} after=3s", state.exit(reason, 3_000L))
        }
        assertEquals("a clock that went back is zero", "manual off reason=left after=0s", ScreenLock.State().also { it.enter(9_000L) }.exit(Exit.LEFT, 1_000L))
    }

    @Test
    fun `back is said once for each mode, and not outside one`() {
        val state = ScreenLock.State()
        assertNull("not in the mode", state.backIgnored())
        state.enter(0L)
        assertEquals("back ignored", state.backIgnored())
        assertNull("only once", state.backIgnored())
        state.exit(Exit.SWIPE, 1_000L)
        state.enter(2_000L)
        assertEquals("a new mode says it again", "back ignored", state.backIgnored())
    }

    @Test
    fun `the display watch says a change of state once, and a state that is not on is the screen gone off`() {
        val watch = ScreenLock.DisplayWatch()
        assertNull("still on", watch.offer(2))
        val off = watch.offer(1)
        assertNotNull(off)
        assertEquals("display state=OFF", off!!.line)
        assertTrue(off.off)
        assertNull("the same state again", watch.offer(1))
        assertEquals("display state=DOZE", watch.offer(3)!!.line)
        assertTrue(watch.offer(4)!!.off)
        val on = watch.offer(2)!!
        assertEquals("display state=ON", on.line)
        assertFalse(on.off)
        assertEquals("state99", ScreenLock.displayName(99))
        assertEquals(listOf("UNKNOWN", "OFF", "ON", "DOZE", "DOZE_SUSPEND", "VR", "ON_SUSPEND"), (0..6).map(ScreenLock::displayName))
    }

    @Test
    fun `the lines sit under the lock tag`() {
        assertEquals("lock", ScreenLock.TAG)
        assertEquals("manual off reason=projection after=0s", ScreenLock.offLine(Exit.PROJECTION, 0))
        assertEquals(2, ScreenLock.DISPLAY_ON)
    }

    @Test
    fun `a move of the thumb counts as a touch, once a second at most, so a drag longer than five seconds keeps the brightness`() {
        val rule = ScreenLock.BrightnessRule(dimAfterMs = 5_000L, startMs = 0L)
        assertTrue(rule.touch(0L, onTrack = true))
        assertFalse("a move right after the touch changes nothing", rule.move(500L))
        assertTrue("a second after, it counts", rule.move(1_000L))
        assertFalse(rule.move(1_999L))
        var now = 2_000L
        while (now <= 20_000L) {
            rule.move(now)
            assertFalse("a drag of ${now / 1_000} s is bright", rule.dimmed(now))
            now += 100L
        }
        assertTrue("five seconds after the last move it goes down", rule.dimmed(25_000L))
        assertTrue("a move brings it back after that, too", rule.move(25_000L))
        assertFalse(rule.dimmed(25_000L))
    }

    @Test
    fun `a touch anywhere on the track brings the brightness back and starts the five seconds again, a touch outside it does nothing`() {
        val rule = ScreenLock.BrightnessRule(dimAfterMs = 5_000L, startMs = 1_000L)
        assertFalse("bright at the start", rule.dimmed(1_000L))
        assertEquals(5_000L, rule.msUntilDim(1_000L))
        assertFalse(rule.dimmed(5_999L))
        assertTrue("dim five seconds after the start", rule.dimmed(6_000L))
        assertEquals(0L, rule.msUntilDim(9_000L))
        assertFalse("a touch outside the track says nothing", rule.touch(7_000L, onTrack = false))
        assertTrue("still dim", rule.dimmed(7_000L))
        assertTrue("a touch on the track", rule.touch(8_000L, onTrack = true))
        assertFalse("bright at once, with no drag", rule.dimmed(8_000L))
        assertEquals(5_000L, rule.msUntilDim(8_000L))
        assertFalse(rule.dimmed(12_999L))
        assertTrue("and down again five seconds after the last touch on the track", rule.dimmed(13_000L))
        assertFalse("a touch elsewhere does not bring it back", rule.touch(14_000L, onTrack = false).also { assertTrue(rule.dimmed(14_000L)) })
    }

    @Test
    fun `the battery line of the card says the percent and the temperature, and a dash for what is not known`() {
        assertEquals("64 % · 37 °C", ScreenLock.batteryText(64, 37.2f))
        assertEquals("64 % · 38 °C", ScreenLock.batteryText(64, 37.5f))
        assertEquals("100 % · -", ScreenLock.batteryText(100, null))
        assertEquals("- · 41 °C", ScreenLock.batteryText(null, 40.6f))
        assertEquals("-", ScreenLock.batteryText(null, null))
    }

    @Test
    fun `the mode says how it began, by the button or by the idle count`() {
        assertEquals("manual on reason=button", ScreenLock.onLine(ScreenLock.Entry.BUTTON))
        assertEquals("manual on reason=idle", ScreenLock.onLine(ScreenLock.Entry.IDLE))
        assertEquals(listOf("button", "idle"), ScreenLock.Entry.entries.map { it.logName })
        val state = ScreenLock.State()
        assertEquals("manual on reason=idle", state.enter(1_000L, ScreenLock.Entry.IDLE))
        assertEquals("manual off reason=swipe after=2s", state.exit(Exit.SWIPE, 3_000L))
    }

    @Test
    fun `the proximity lock lines are short, and the display's proximity off and on are said once per change`() {
        assertEquals("proximity lock on (enter)", ScreenLock.proximityLine(true, ScreenLock.PROXIMITY_ENTER))
        assertEquals("proximity lock on (resume)", ScreenLock.proximityLine(true, ScreenLock.PROXIMITY_RESUME))
        assertEquals("proximity lock off (swipe)", ScreenLock.proximityLine(false, Exit.SWIPE.logName))
        assertEquals("proximity lock off (paused)", ScreenLock.proximityLine(false, ScreenLock.PROXIMITY_PAUSE))
        assertEquals("proximity lock off (destroyed)", ScreenLock.proximityLine(false, ScreenLock.PROXIMITY_DESTROY))
        assertEquals("proximity lock not supported", ScreenLock.PROXIMITY_UNSUPPORTED_LINE)
        val edge = ScreenLock.ProximityEdge()
        assertNull("an off that is not the lock's says nothing here", edge.off(covered = false, sensor = "far"))
        assertNull("and neither does a display coming on that never went off by it", edge.on())
        assertEquals("display off by proximity sensor=near", edge.off(covered = true, sensor = "near"))
        assertNull("the same off is said once", edge.off(covered = true, sensor = "near"))
        assertEquals("display on again (proximity uncovered)", edge.on())
        assertNull(edge.on())
        assertEquals("again, the next time", "display off by proximity sensor=unknown", edge.off(covered = true, sensor = "unknown"))
    }
}
