package com.reau.app.dash

import com.reau.app.aa.AaScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * With the info ring on, the dash's down button in its map mode (0x15) shows it and hides it ([RingCycle]); the maneuver ring is never
 * touched by it and keeps its own rules. With the info ring off nothing of this happens.
 */
class DashSessionRingCycleTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)

    @After
    fun tearDown() = scope.cancel()

    private class Memory(override var step: Int = 0) : RingStepMemory

    private fun maneuverOnTheGlass() = session.ringVisible

    @Test
    fun `with another action on down the press does not switch the info ring`() {
        session.ringCycleOn = true
        session.infoRingPress = false
        session.ringAfterPress(0x15)
        assertEquals("the info ring stays where it was", true, session.infoVisible)
        session.infoRingPress = true
        session.ringAfterPress(0x15)
        assertEquals("the default switches it", false, session.infoVisible)
    }

    @Test
    fun `down shows and hides the info ring, and the maneuver ring stays as it was`() {
        session.ringCycleOn = true
        assertEquals("both", true to true, maneuverOnTheGlass() to session.infoVisible)
        assertEquals("no card: the info ring is on the video", false, session.ringAfterPress(0x15))
        assertEquals("the maneuver ring alone", true to false, maneuverOnTheGlass() to session.infoVisible)
        session.ringAfterPress(0x15)
        assertEquals("both again", true to true, maneuverOnTheGlass() to session.infoVisible)
    }

    @Test
    fun `with the maneuver switch off the maneuver ring keeps following its own rules while down moves the info ring`() {
        session.ringCycleOn = true
        session.maneuverCircleEnabled = false
        assertEquals("hidden until a press or a navigation screen", false to true, maneuverOnTheGlass() to session.infoVisible)
        assertEquals("down in the map mode shows the maneuver ring by the old rule", true, session.ringAfterPress(0x15))
        assertEquals("and hides the info ring", true to false, maneuverOnTheGlass() to session.infoVisible)
        assertEquals("a media mode press hides the maneuver ring by the old rule", true, session.ringAfterPress(0x05))
        assertEquals(false to false, maneuverOnTheGlass() to session.infoVisible)
    }

    @Test
    fun `the info ring in the maneuver ring's place is shown and hidden the same way`() {
        session.ringCycleOn = true
        session.infoInPlace = true
        assertEquals(true, session.infoVisible)
        session.ringAfterPress(0x15)
        assertEquals(false, session.infoVisible)
        assertEquals("the maneuver ring was not touched", true, maneuverOnTheGlass())
    }

    @Test
    fun `the middle and the media buttons never switch the info ring, with the music card up or not`() {
        session.ringCycleOn = true
        session.ringFollowsScreen(AaScreen.NAV)
        for (musicCardUp in listOf(false, true)) {
            session.musicShown = musicCardUp
            for (code in listOf(0x18, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x12, 0x13, 0x14)) session.ringAfterPress(code)
        }
        assertEquals("the info ring did not move", true, session.infoVisible)
        assertEquals(0, session.ringStep)
    }

    @Test
    fun `on the app menu and the other apps the info ring goes away, and the navigation brings it back as it was`() {
        val nav = AaScreen.NAV
        for (away in listOf(AaScreen.MENU, AaScreen.APPS)) {
            for (step in 0 until RingCycle.STEPS) {
                session.ringCycleOn = true
                session.ringStep = step
                session.ringFollowsScreen(nav)
                val before = session.infoVisible
                session.ringFollowsScreen(away)
                assertEquals("$away at step $step", false, session.infoVisible)
                assertEquals("the maneuver ring is not the info ring's business", true, maneuverOnTheGlass())
                session.ringFollowsScreen(nav)
                assertEquals("back from $away at step $step", before, session.infoVisible)
            }
        }
    }

    @Test
    fun `the info ring goes away on the menu in its own place too`() {
        session.ringCycleOn = true
        session.infoInPlace = true
        for (step in 0 until RingCycle.STEPS) {
            session.ringStep = step
            session.ringFollowsScreen(AaScreen.MENU)
            assertEquals("step $step", false, session.infoVisible)
        }
    }

    @Test
    fun `a press on the menu does not switch the info ring, so the navigation is as the rider left it`() {
        session.ringCycleOn = true
        session.ringFollowsScreen(AaScreen.NAV)
        session.ringAfterPress(0x15)
        assertEquals(1, session.ringStep)
        session.ringFollowsScreen(AaScreen.MENU)
        session.ringAfterPress(0x15)
        assertEquals(1, session.ringStep)
        session.ringFollowsScreen(AaScreen.NAV)
        assertEquals("hidden, as before", false, session.infoVisible)
    }

    @Test
    fun `a screen not known yet takes the info ring away from nowhere`() {
        session.ringCycleOn = true
        session.ringFollowsScreen(AaScreen.UNKNOWN)
        assertEquals(true, session.infoVisible)
    }

    @Test
    fun `the maneuver ring follows the screen with its switch off whether the info ring is on or not`() {
        session.maneuverCircleEnabled = false
        for (infoRing in listOf(false, true)) {
            session.ringCycleOn = infoRing
            session.ringAfterPress(0x13) // shown by a map mode press
            assertEquals("info ring $infoRing: the menu hides it", true, session.screenMovedRings(AaScreen.MENU))
            assertEquals("info ring $infoRing: the navigation shows it", true, session.screenMovedRings(AaScreen.NAV))
            assertEquals("info ring $infoRing: shown", true, maneuverOnTheGlass())
        }
    }

    @Test
    fun `the cycle does not take the place of the maneuver ring's switch`() {
        session.maneuverCircleEnabled = false
        session.ringCycleOn = true
        assertEquals(false, maneuverOnTheGlass())
        session.maneuverCircleEnabled = true
        assertEquals(true, maneuverOnTheGlass())
    }

    @Test
    fun `with the info ring off down is not the info ring's switch and the info ring is never shown`() {
        session.maneuverCircleEnabled = false
        assertEquals(false to false, maneuverOnTheGlass() to session.infoVisible)
        assertEquals(true, session.ringAfterPress(0x13))
        assertEquals(true to false, maneuverOnTheGlass() to session.infoVisible)
        session.ringAfterPress(0x15)
        assertEquals(0, session.ringStep)
    }

    @Test
    fun `a call shows the maneuver ring whatever the switch, the info ring keeps its choice`() {
        session.ringCycleOn = true
        session.maneuverCircleEnabled = false
        session.ringStep = 1
        assertEquals(false to false, maneuverOnTheGlass() to session.infoVisible)
        session.showCaller("Ana")
        assertEquals(true to false, maneuverOnTheGlass() to session.infoVisible)
        session.clearCaller()
        assertEquals(false, maneuverOnTheGlass())
    }

    @Test
    fun `the step is kept and read back, and an older build's step 2 counts as hidden`() {
        val memory = Memory()
        session.ringStepMemory = memory
        session.ringCycleOn = true
        session.ringAfterPress(0x15)
        assertEquals(1, memory.step)
        session.ringAfterPress(0x15)
        assertEquals(0, memory.step)
        session.ringStep = 2
        assertEquals(1, session.ringStep)
        assertEquals(false, session.infoVisible)
        session.ringStep = -3
        assertEquals(1, session.ringStep)
    }
}
