package com.reau.app.service

import com.reau.app.service.ScreenKeepManual.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the "Bloquear a tela" button: when it is there, what a swipe up is, and the state that goes in and out with its ride log lines. */
class ScreenKeepManualTest {
    @Test
    fun `the button is there with the projection on, whatever the switch of the lit screen says, and not otherwise`() {
        assertTrue(ScreenKeepManual.available(projectionOn = true))
        assertFalse(ScreenKeepManual.available(projectionOn = false))
    }

    @Test
    fun `a swipe up of the threshold or more leaves, a short one, a swipe down and a tap do not`() {
        val threshold = 300f
        assertTrue(ScreenKeepManual.swipedUp(-300f, threshold))
        assertTrue(ScreenKeepManual.swipedUp(-900f, threshold))
        assertFalse("a loose touch", ScreenKeepManual.swipedUp(-20f, threshold))
        assertFalse("just short", ScreenKeepManual.swipedUp(-299f, threshold))
        assertFalse("down", ScreenKeepManual.swipedUp(500f, threshold))
        assertFalse("a tap", ScreenKeepManual.swipedUp(0f, threshold))
    }

    @Test
    fun `going in says manual on once, and going out says why and how long, once`() {
        val state = ScreenKeepManual.State()
        assertFalse(state.active)
        assertNull("nothing to leave", state.exit(Exit.SWIPE, 5_000L))
        assertEquals("manual on", state.enter(10_000L))
        assertTrue(state.active)
        assertNull("already in", state.enter(11_000L))
        assertEquals("manual off reason=swipe after=62s", state.exit(Exit.SWIPE, 72_900L))
        assertFalse(state.active)
        assertNull("already out", state.exit(Exit.BACK, 80_000L))
    }

    @Test
    fun `every reason has its own word in the log and the mode can go in again after leaving`() {
        assertEquals(listOf("swipe", "back", "projection", "screenoff"), Exit.entries.map { it.logName })
        val state = ScreenKeepManual.State()
        for (reason in Exit.entries) {
            assertEquals("manual on", state.enter(0L))
            assertEquals("manual off reason=${reason.logName} after=3s", state.exit(reason, 3_000L))
        }
        assertEquals("a clock that went back is zero", "manual off reason=back after=0s", ScreenKeepManual.State().also { it.enter(9_000L) }.exit(Exit.BACK, 1_000L))
    }

    @Test
    fun `the lines are short and sit under the screen-keep tag`() {
        assertEquals("manual on", ScreenKeepManual.ON_LINE)
        assertEquals("manual off reason=projection after=0s", ScreenKeepManual.offLine(Exit.PROJECTION, 0))
        assertEquals("screen-keep", ScreenKeepPolicy.TAG)
    }
}
