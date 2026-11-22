package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick on a call with REau's own call card on ([JoystickMap.callCard]): the dash is not told of the call, so it shows no call screen and sends the plain codes of its
 * map and media modes. The same table in both modes (P32, ride of 22/11): ringing, the left answers, the right and the middle decline, up and down do nothing; on a
 * call, the left and the right do nothing, the middle ends it (even under the volume lock), up and down are the call's volume (the map mode's up is the dash's back and
 * its down the code 0x15, which neither does the page's action nor switches the info ring); the lock lasts through a call. With the switch off the joystick is what it was.
 */
class JoystickCallCardTest {
    private val mediaLeft = DashButtonEvent.PrevTrack(0x0A)
    private val mediaRight = DashButtonEvent.NextTrack(0x09)
    private val mediaCenter = DashButtonEvent.PlayPause(0x05)
    private val mediaUp = DashButtonEvent.VolumeUp(0x06)
    private val mediaDown = DashButtonEvent.VolumeDown(0x07)
    private val mapLeft = DashButtonEvent.ZoomIn(0x14)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)
    private val mapBack = DashButtonEvent.JoystickBack(0x12)
    private val mapDown = DashButtonEvent.Unknown(0x15)

    private var call = CallState.IDLE

    private fun map(callCard: Boolean, page: JoystickBindings = JoystickBindings.DEFAULT) = JoystickMap(page, callState = { call }).also { it.callCard = callCard }

    private fun sent(callCard: Boolean, state: CallState, event: DashButtonEvent, page: JoystickBindings = JoystickBindings.DEFAULT): List<AaCommand> {
        call = state
        return map(callCard, page).press(event, 10_000).sent
    }

    private val ringingEvents = listOf(mediaLeft, mediaRight, mediaCenter, mediaUp, mediaDown, mapLeft, mapRight, mapCenter, mapBack, mapDown)

    @Test
    fun `ringing, the left answers, the right and the middle decline, in both modes`() {
        for (left in listOf(mediaLeft, mapLeft)) assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), sent(true, CallState.RINGING, left))
        for (right in listOf(mediaRight, mapRight)) assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(true, CallState.RINGING, right))
        for (center in listOf(mediaCenter, mapCenter)) assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(true, CallState.RINGING, center))
    }

    @Test
    fun `ringing, up and down do nothing in both modes, whatever the page says, and the map's up sends nothing to Android Auto`() {
        val zoom = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.ZOOM_IN)
        for (page in listOf(JoystickBindings.DEFAULT, zoom)) {
            for (event in listOf(mediaUp, mediaDown, mapBack, mapDown)) {
                val press = map(true, page).also { call = CallState.RINGING }.press(event, 10_000)
                assertEquals("$event", emptyList<AaCommand>(), press.sent)
                assertNull("$event", press.echo)
                assertTrue(press.lines.toString(), press.lines.single().endsWith(": while it rings, nothing"))
            }
        }
        assertEquals("media down is the page's home with the card off", listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME)), sent(false, CallState.RINGING, mediaDown))
        assertTrue("the dash's back goes to Android Auto with the card off", sent(false, CallState.RINGING, mapBack).isNotEmpty())
    }

    @Test
    fun `on a call the left and the right do nothing, in both modes, with a line saying so`() {
        call = CallState.IN_CALL
        for (event in listOf(mediaLeft, mediaRight, mapLeft, mapRight)) {
            val press = map(true).press(event, 10_000)
            assertEquals("$event", emptyList<AaCommand>(), press.sent)
            assertNull("$event", press.echo)
            assertTrue(press.lines.toString(), press.lines.single().endsWith(": on a call, nothing"))
        }
    }

    @Test
    fun `on a call the middle ends it, in both modes, saying so on the dash`() {
        call = CallState.IN_CALL
        for (center in listOf(mediaCenter, mapCenter)) {
            val press = map(true).press(center, 10_000)
            assertEquals("$center", listOf<AaCommand>(AaCommand.EndCall), press.sent)
            assertEquals(EchoLabel.END_CALL, press.echo)
        }
    }

    @Test
    fun `on a call up and down are the call's volume in both modes, whatever the page says, with no echo`() {
        val zoom = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.ZOOM_IN)
            .with(JoystickBindings.INFO_RING_SLOT, Action.HOME)
        for (page in listOf(JoystickBindings.DEFAULT, zoom)) {
            for (up in listOf(mediaUp, mapBack)) assertEquals("$up", listOf<AaCommand>(AaCommand.CallVolume(1)), sent(true, CallState.IN_CALL, up, page))
            for (down in listOf(mediaDown, mapDown)) assertEquals("$down", listOf<AaCommand>(AaCommand.CallVolume(-1)), sent(true, CallState.IN_CALL, down, page))
        }
        call = CallState.IN_CALL
        assertNull(map(true).press(mapDown, 10_000).echo)
    }

    @Test
    fun `the ride of 22-11, a call in the map mode and the down twice, is the call's volume down twice, with no home and no ring`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        for (at in listOf(10_000L, 11_500L)) {
            val press = joystick.press(mapDown, at)
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), press.sent)
            assertTrue(press.lines.toString(), press.lines.single() == "call volume -1")
            assertTrue("the session is told to leave the info ring alone", joystick.wouldDrop(mapDown, at + 1))
        }
    }

    @Test
    fun `the info ring's switch is left alone while the phone rings or a call goes on, only with the card on`() {
        for (state in listOf(CallState.RINGING, CallState.IN_CALL)) {
            call = state
            assertTrue("$state", map(true).wouldDrop(mapDown, 10_000))
            assertTrue("$state: with the lock on too", map(true).also { it.volumeLock.enabled = true }.wouldDrop(mapDown, 10_000))
            assertFalse("$state: the card off changes nothing", map(false).wouldDrop(mapDown, 10_000))
        }
        call = CallState.IDLE
        assertFalse(map(true).wouldDrop(mapDown, 10_000))
    }

    @Test
    fun `under the lock a call keeps it, the volume goes on and renews it, the middle ends the call and the sides are dropped`() {
        call = CallState.IN_CALL
        for (up in listOf(mediaUp, mapBack)) for (down in listOf(mediaDown, mapDown)) {
            val joystick = map(true).also { it.volumeLock.enabled = true }
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(up, 0).sent)
            assertEquals("a call volume tap locks", 5_000L, joystick.volumeLock.leftMs(0))
            for (side in listOf(mediaLeft, mediaRight, mapLeft, mapRight)) {
                val dropped = joystick.press(side, 1_000)
                assertEquals("$side", emptyList<AaCommand>(), dropped.sent)
                assertTrue(dropped.lines.toString(), dropped.lines.single().startsWith("volume lock: "))
            }
            assertEquals("a dropped side renews nothing", 4_000L, joystick.volumeLock.leftMs(1_000))
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), joystick.press(down, 2_000).sent)
            assertEquals("the volume tap renewed it", 5_000L, joystick.volumeLock.leftMs(2_000))
            assertEquals("both ways of up and down are taken under the lock", listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(up, 3_000).sent)
            assertTrue("the lock is not lifted by the call", joystick.wouldDrop(mediaLeft, 2_100))
            if (down != mapDown) assertFalse("the call's volume is not dropped", joystick.wouldDrop(down, 2_100))
        }
    }

    @Test
    fun `the middle always ends the call, even under the lock, in both modes and by the dash's own code`() {
        call = CallState.IN_CALL
        for (center in listOf(mediaCenter, mapCenter, DashButtonEvent.Unknown(0x16))) {
            val joystick = map(true).also { it.volumeLock.enabled = true }
            joystick.press(mediaUp, 0)
            assertTrue("$center is not dropped", !joystick.wouldDrop(center, 100))
            val press = joystick.press(center, 100)
            assertEquals("$center", listOf<AaCommand>(AaCommand.EndCall), press.sent)
            assertEquals(EchoLabel.END_CALL, press.echo)
        }
    }

    @Test
    fun `with the card off a call that comes lifts the lock, and the right still ends it`() {
        val volumePage = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.VOLUME_UP)
        val joystick = map(false, volumePage).also { it.volumeLock.enabled = true }
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), joystick.press(mediaUp, 0).sent)
        call = CallState.IN_CALL
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), joystick.press(mediaRight, 1_000).sent)
        assertEquals(0L, joystick.volumeLock.leftMs(1_001))
    }

    @Test
    fun `with the card off the lock is lifted by a call and the call's volume locks nothing`() {
        val volumePage = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.VOLUME_UP)
        call = CallState.IN_CALL
        val joystick = map(false, volumePage).also { it.volumeLock.enabled = true }
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(DashButtonEvent.Unknown(0x03), 0).sent)
        assertEquals(0L, joystick.volumeLock.leftMs(0))
    }

    @Test
    fun `with the card off the joystick is what it was on a call (the right still ends it, the middle is the page's)`() {
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(false, CallState.IN_CALL, mediaRight))
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(false, CallState.IN_CALL, mapRight))
        assertEquals(emptyList<AaCommand>(), sent(false, CallState.IN_CALL, mediaLeft))
        assertEquals("the page's confirm", listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER)), sent(false, CallState.IN_CALL, mediaCenter))
        assertEquals(listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME)), sent(false, CallState.IN_CALL, mediaDown))
        assertEquals("the dash's own call codes", listOf<AaCommand>(AaCommand.EndCall), sent(false, CallState.IN_CALL, DashButtonEvent.Unknown(0x16)))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), sent(false, CallState.IN_CALL, DashButtonEvent.Unknown(0x03)))
        assertTrue(sent(false, CallState.IN_CALL, mapBack).isNotEmpty())
    }

    @Test
    fun `with no call the card changes nothing`() {
        for (event in ringingEvents) assertEquals("$event", sent(false, CallState.IDLE, event), sent(true, CallState.IDLE, event))
        assertTrue(sent(true, CallState.IDLE, mapBack).isNotEmpty())
    }

    @Test
    fun `the dash's own call codes still work with the card on`() {
        assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), sent(true, CallState.RINGING, DashButtonEvent.Unknown(0x02)))
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(true, CallState.IN_CALL, DashButtonEvent.Unknown(0x16)))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), sent(true, CallState.IN_CALL, DashButtonEvent.Unknown(0x04)))
    }

    @Test
    fun `a middle right after a side, the dash's one gesture, is not a tap, and later it ends the call`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        joystick.press(mapLeft, 1_000)
        assertEquals("100 ms after a side", emptyList<AaCommand>(), joystick.press(mapCenter, 1_100).sent)
        joystick.press(mapLeft, 3_000)
        assertEquals("200 ms after a side: the dash's ghost before its own press of the middle", listOf<AaCommand>(AaCommand.EndCall), joystick.press(mapCenter, 3_200).sent)
        val media = map(true)
        media.press(mediaLeft, 1_000)
        assertEquals("the media middle goes from 100 ms", listOf<AaCommand>(AaCommand.EndCall), media.press(mediaCenter, 1_120).sent)
    }

    @Test
    fun `the middle that just ended the call is held back for three seconds, the same tap again does nothing`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), joystick.press(mediaCenter, 1_000).sent)
        assertEquals(emptyList<AaCommand>(), joystick.press(mediaCenter, 2_500).sent)
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), joystick.press(mediaCenter, 4_100).sent)
    }

    @Test
    fun `an up right after a side is the same gesture on a call too, and an up alone is the volume`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        joystick.press(mediaLeft, 1_000)
        assertEquals(emptyList<AaCommand>(), joystick.press(mediaUp, 1_100).sent)
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(mediaUp, 2_000).sent)
    }

    @Test
    fun `the volume lock never holds the call's keys back, with the card on`() {
        val joystick = map(true, JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.VOLUME_UP))
        joystick.volumeLock.enabled = true
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), joystick.press(mediaUp, 0).sent)
        call = CallState.RINGING
        assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), joystick.press(mediaLeft, 1_000).sent)
    }

    @Test
    fun `the live sides give the joystick the switch, at the start and at each change`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "joystick?.callCard = on" in live)
        assertTrue(live, "callCardSwitch(setup.settings.callCard)" in live)
        assertTrue(live, "callCardSwitch(settings.callCard)" in live)
    }
}
