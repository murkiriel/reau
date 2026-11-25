package com.reau.app.dash

import com.reau.app.aa.AaCommand
import com.reau.app.aa.JoystickBindings
import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.aa.JoystickMap
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Under the joystick's volume lock no other command is taken, and the info ring's switch on the dash's down button is one: the session asks the joystick whether the press
 * would be dropped ([DashSession.pressDropped], [JoystickMap.wouldDrop]) and, if so, does not switch the ring. The volume taps themselves, and everything with the lock off or
 * over, are as they were.
 */
class DashSessionRingLockTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)
    private var now = 1_000L
    private var call = CallState.IDLE
    private val page = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.VOLUME_UP)
    private val joystick = JoystickMap(page, callState = { call }).also { it.volumeLock.enabled = true }

    init {
        session.ringCycleOn = true
        session.infoRingPress = true
        session.pressDropped = { code -> joystick.wouldDrop(DashButtonEvent.fromCode(code), now) }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `without the lock down switches the info ring`() {
        assertTrue(session.infoVisible)
        session.ringAfterPress(0x15)
        assertFalse(session.infoVisible)
    }

    @Test
    fun `under the lock down does not switch the info ring, and after the lock it does`() {
        joystick.press(DashButtonEvent.VolumeUp(0x06), now)
        now += 2_000
        session.ringAfterPress(0x15)
        assertTrue("the ring did not move under the lock", session.infoVisible)
        assertEquals(0, session.ringStep)
        now += 3_000
        assertEquals("the lock is over at five seconds", 0L, joystick.volumeLock.leftMs(now))
        session.ringAfterPress(0x15)
        assertFalse(session.infoVisible)
    }

    @Test
    fun `the press the joystick would take, a volume tap, is no loss to the ring, and a call ends the lock for the ring too`() {
        joystick.press(DashButtonEvent.VolumeUp(0x06), now)
        now += 100
        assertFalse("a volume tap is taken", joystick.wouldDrop(DashButtonEvent.VolumeUp(0x06), now))
        call = CallState.RINGING
        session.ringAfterPress(0x15)
        assertFalse("a call ringing breaks the lock", session.infoVisible)
    }

    @Test
    fun `with the call card on a call, ringing or going on, the down button leaves the info ring alone, and with it off the ring is as it was`() {
        joystick.callCard = true
        for (state in listOf(CallState.RINGING, CallState.IN_CALL)) {
            call = state
            session.ringAfterPress(0x15)
            session.ringAfterPress(0x15)
            assertTrue("$state: the ring did not move", session.infoVisible)
            assertEquals(0, session.ringStep)
        }
        call = CallState.IDLE
        session.ringAfterPress(0x15)
        assertFalse("with no call the down button switches the ring again", session.infoVisible)
        joystick.callCard = false
        call = CallState.IN_CALL
        session.ringAfterPress(0x15)
        assertTrue("the card off: nothing changed from before (the dash is told of the call)", session.infoVisible)
    }

    @Test
    fun `the maneuver ring still follows the dash's mode under the lock, as it is no command`() {
        session.maneuverCircleEnabled = false
        joystick.press(DashButtonEvent.VolumeUp(0x06), now)
        assertTrue("the map mode's press shows the maneuver ring", session.ringAfterPress(0x13))
        assertTrue(session.ringVisible)
    }

    @Test
    fun `wouldDrop says what press does, for every code, in every state of the lock and of the call`() {
        val codes = listOf(0x05, 0x06, 0x07, 0x09, 0x0A, 0x12, 0x13, 0x14, 0x15, 0x18, 0x21)
        for (state in CallState.entries) for (afterMs in listOf(100L, 4_900L, 5_100L)) for (code in codes) {
            call = CallState.IDLE
            val map = JoystickMap(page, callState = { call }).also { it.volumeLock.enabled = true }
            map.press(DashButtonEvent.VolumeUp(0x06), 0)
            call = state
            val event = DashButtonEvent.fromCode(code)
            val predicted = map.wouldDrop(event, afterMs)
            val dropped = map.press(event, afterMs).lines.any { it.startsWith("volume lock:") }
            assertEquals("call $state, code 0x${code.toString(16)}, $afterMs ms after the volume tap", dropped, predicted)
        }
        // and a volume tap and the rest of the page with no lock
        val free = JoystickMap(page).also { it.volumeLock.enabled = true }
        assertFalse(free.wouldDrop(DashButtonEvent.JoystickBack(0x12), 0))
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), free.press(DashButtonEvent.VolumeUp(0x06), 0).sent)
    }

    @Test
    fun `wouldDrop changes nothing, not even the lock a call would break`() {
        joystick.press(DashButtonEvent.VolumeUp(0x06), now)
        call = CallState.RINGING
        assertFalse(joystick.wouldDrop(DashButtonEvent.JoystickBack(0x12), now + 10))
        assertTrue("the lock is still there until the press itself", joystick.volumeLock.leftMs(now + 10) > 0L)
    }

    @Test
    fun `with the call card on, a ringing phone or a call moves no ring, not the maneuver ring and not the info ring`() {
        joystick.callCard = true
        session.callOwnsPress = joystick::callOwnsPress
        session.maneuverCircleEnabled = false
        // the ride of 23/11/2026: the middle that ended the call hid the ring, the left that answered showed it
        for (state in listOf(CallState.RINGING, CallState.IN_CALL)) {
            call = state
            for (code in listOf(0x13, 0x14, 0x15, 0x12, 0x18, 0x05, 0x06, 0x07, 0x09, 0x0A)) {
                assertFalse("0x${code.toString(16)} in $state", session.ringAfterPress(code))
            }
            assertFalse("the maneuver ring stays hidden", session.ringVisible)
            assertEquals("the info ring stays as it was", 0, session.ringStep)
        }
        call = CallState.IDLE
        assertTrue("with no call the map mode's press shows it again", session.ringAfterPress(0x13))
        assertTrue(session.ringAfterPress(0x05))
        session.ringAfterPress(0x13)
        session.ringAfterPress(0x15)
        assertEquals("and the info ring's switch works again", 1, session.ringStep)
    }

    @Test
    fun `with the call card off a call moves the rings as before`() {
        session.callOwnsPress = joystick::callOwnsPress
        session.maneuverCircleEnabled = false
        call = CallState.IN_CALL
        assertTrue(session.ringAfterPress(0x13))
        assertTrue(session.ringAfterPress(0x05))
    }

    @Test
    fun `the live sides give the session the joystick's answer`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "session.pressDropped = { code ->" in live)
        assertTrue(live, "session.callOwnsPress = joystick::callOwnsPress" in live)
        assertTrue(live, "joystick.wouldDrop(com.reau.app.dash.protocol.DashButtonEvent.fromByte(code.toByte()), com.reau.app.util.MonotonicClock.nowMs())" in live)
        val dash = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue(dash, "if (infoRingSwitchable && RingCycle.advancesOn(code) && !pressDropped(code)) switchInfoRing(code)" in dash)
        assertTrue(dash, "if (callOwnsPress()) return false" in dash)
    }
}
