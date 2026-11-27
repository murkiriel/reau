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
 * map and media modes. The same table in both modes (decided on 23/11/2026 after a ride: the dash never sent the map mode's "up" on a call): ringing, the left answers, the right
 * and the middle decline, up and down do nothing; on a call, the right raises the call's volume one step and the left lowers it (0x13 and 0x14 in the map mode, 0x09 and 0x0A in
 * the media mode), the middle ends it (even under the volume lock) and up and down do nothing (the dash's back 0x12, the code 0x15, the media volume codes 0x06 and 0x07 and the
 * call screen's 0x03 and 0x04), whatever the page says; the lock lasts through a call and takes the sides and the middle. With the switch off the joystick is what it was.
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
        assertEquals("the dash's back sends nothing to Android Auto with the card off either", emptyList<AaCommand>(), sent(false, CallState.RINGING, mapBack))
    }

    @Test
    fun `on a call the right raises the call volume and the left lowers it, in both modes, with a line saying so and no echo`() {
        call = CallState.IN_CALL
        for (right in listOf(mediaRight, mapRight)) {
            val press = map(true).press(right, 10_000)
            assertEquals("$right", listOf<AaCommand>(AaCommand.CallVolume(1)), press.sent)
            assertEquals(listOf("call: right -> call volume +1"), press.lines)
            assertNull("$right", press.echo)
        }
        for (left in listOf(mediaLeft, mapLeft)) {
            val press = map(true).press(left, 10_000)
            assertEquals("$left", listOf<AaCommand>(AaCommand.CallVolume(-1)), press.sent)
            assertEquals(listOf("call: left -> call volume -1"), press.lines)
            assertNull("$left", press.echo)
        }
    }

    @Test
    fun `the sides on a call are the call volume whatever the page says, and a side held back by the answer is not`() {
        val zoom = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE), Action.HOME).with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.HOME)
        for (event in listOf(mediaRight, mapLeft)) assertEquals(1, sent(true, CallState.IN_CALL, event, zoom).size)
        call = CallState.RINGING
        val joystick = map(true)
        assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), joystick.press(mapLeft, 1_000).sent)
        call = CallState.IN_CALL
        assertEquals("the left that answered is the volume down right after", listOf<AaCommand>(AaCommand.CallVolume(-1)), joystick.press(mapLeft, 1_500).sent)
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(mapRight, 1_700).sent)
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
    fun `on a call up and down do nothing in both modes, whatever the page says, with every code they come as`() {
        val zoom = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.ZOOM_IN)
            .with(JoystickBindings.INFO_RING_SLOT, Action.HOME)
        val codes = listOf(mediaUp, mapBack, mediaDown, mapDown, DashButtonEvent.Unknown(0x03), DashButtonEvent.Unknown(0x04))
        for (page in listOf(JoystickBindings.DEFAULT, zoom)) {
            for (event in codes) {
                call = CallState.IN_CALL
                val press = map(true, page).press(event, 10_000)
                assertEquals("$event", emptyList<AaCommand>(), press.sent)
                assertNull("$event", press.echo)
                assertTrue(press.lines.toString(), press.lines.single().endsWith(": on a call, nothing"))
            }
        }
    }

    @Test
    fun `the ride of 23-11, a call in the map mode, the down four times, the sides and the middle, is the sides' volume and the call ended`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        val ride = listOf(
            26_702L to mapDown, 28_027L to mapDown, 29_583L to mapDown, 29_924L to mapDown,
            39_450L to mapLeft, 40_134L to mapLeft,
            41_027L to mapRight, 41_561L to mapRight, 46_641L to mapRight,
            47_866L to mapCenter,
        )
        val sent = ride.map { (at, event) -> joystick.press(event, at).sent }
        assertEquals("the down does nothing", List(4) { emptyList<AaCommand>() }, sent.take(4))
        assertEquals(List(2) { listOf<AaCommand>(AaCommand.CallVolume(-1)) }, sent.subList(4, 6))
        assertEquals(List(3) { listOf<AaCommand>(AaCommand.CallVolume(1)) }, sent.subList(6, 9))
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent.last())
        assertTrue("the session is told to leave the info ring alone", joystick.wouldDrop(mapDown, 30_000))
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
    fun `the call owns the presses, for the rings, while the phone rings or a call goes on with the card on, and never otherwise`() {
        for ((card, state, owns) in listOf(
            Triple(true, CallState.RINGING, true), Triple(true, CallState.IN_CALL, true), Triple(true, CallState.IDLE, false),
            Triple(false, CallState.RINGING, false), Triple(false, CallState.IN_CALL, false), Triple(false, CallState.IDLE, false),
        )) {
            call = state
            assertEquals("card=$card $state", owns, map(card).callOwnsPress())
        }
    }

    @Test
    fun `under the lock a call keeps it, the sides' volume goes on and renews it, the middle ends the call and up and down are dropped`() {
        call = CallState.IN_CALL
        for (side in listOf(mediaRight, mapRight)) for (other in listOf(mediaLeft, mapLeft)) {
            val joystick = map(true).also { it.volumeLock.enabled = true }
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(side, 0).sent)
            assertEquals("a call volume tap locks", 5_000L, joystick.volumeLock.leftMs(0))
            for (upOrDown in listOf(mediaUp, mediaDown, mapBack, mapDown)) {
                val dropped = joystick.press(upOrDown, 1_000)
                assertEquals("$upOrDown", emptyList<AaCommand>(), dropped.sent)
                assertTrue(dropped.lines.toString(), dropped.lines.single().startsWith("volume lock: "))
                assertTrue("$upOrDown is dropped", joystick.wouldDrop(upOrDown, 1_000))
            }
            assertEquals("a dropped tap renews nothing", 4_000L, joystick.volumeLock.leftMs(1_000))
            assertFalse("the call's volume is not dropped", joystick.wouldDrop(other, 2_000))
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), joystick.press(other, 2_000).sent)
            assertEquals("the volume tap renewed it", 5_000L, joystick.volumeLock.leftMs(2_000))
            assertEquals("both sides are taken under the lock", listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(side, 3_000).sent)
            assertTrue("the lock is not lifted by the call", joystick.wouldDrop(mapDown, 3_100))
        }
    }

    @Test
    fun `under the lock the dash's 0x03 and 0x04 are dropped on a call with the card on`() {
        call = CallState.IN_CALL
        val joystick = map(true).also { it.volumeLock.enabled = true }
        joystick.press(mapRight, 0)
        for (code in listOf(0x03, 0x04)) {
            val dropped = joystick.press(DashButtonEvent.Unknown(code), 1_000L + code)
            assertEquals(emptyList<AaCommand>(), dropped.sent)
            assertTrue(dropped.lines.toString(), dropped.lines.single().startsWith("volume lock: "))
        }
    }

    @Test
    fun `the middle always ends the call, even under the lock, in both modes and by the dash's own code`() {
        call = CallState.IN_CALL
        for (center in listOf(mediaCenter, mapCenter, DashButtonEvent.Unknown(0x16))) {
            val joystick = map(true).also { it.volumeLock.enabled = true }
            joystick.press(mediaRight, 0)
            assertTrue("$center is not dropped", !joystick.wouldDrop(center, 1_000))
            val press = joystick.press(center, 1_000)
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
        assertEquals("the dash's back is its own exit, nothing for Android Auto", emptyList<AaCommand>(), sent(false, CallState.IN_CALL, mapBack))
    }

    @Test
    fun `with no call the card changes nothing`() {
        for (event in ringingEvents) assertEquals("$event", sent(false, CallState.IDLE, event), sent(true, CallState.IDLE, event))
        assertEquals(emptyList<AaCommand>(), sent(true, CallState.IDLE, mapBack))
    }

    @Test
    fun `the dash's own call codes still work with the card on`() {
        assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), sent(true, CallState.RINGING, DashButtonEvent.Unknown(0x02)))
        assertEquals(listOf<AaCommand>(AaCommand.EndCall), sent(true, CallState.IN_CALL, DashButtonEvent.Unknown(0x16)))
        assertEquals("with the card on the dash is not told of the call: its 0x04 is nothing", emptyList<AaCommand>(), sent(true, CallState.IN_CALL, DashButtonEvent.Unknown(0x04)))
        assertEquals(emptyList<AaCommand>(), sent(true, CallState.IN_CALL, DashButtonEvent.Unknown(0x03)))
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
    fun `an up or a down right after a side, the dash's ghost, does nothing on a call, and so does one alone`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(mediaRight, 1_000).sent)
        for ((at, event) in listOf(1_100L to mediaUp, 1_120L to mapDown, 2_000L to mediaUp, 2_500L to mapDown, 3_000L to mapBack)) {
            assertEquals("$event at $at", emptyList<AaCommand>(), joystick.press(event, at).sent)
        }
        assertEquals(listOf("right after a side, ignored"), map(true).also { it.press(mapLeft, 1_000) }.press(mapDown, 1_100).lines)
    }

    @Test
    fun `the sides are one volume step each, the dash's bounce is one, and two taps apart are two`() {
        call = CallState.IN_CALL
        val joystick = map(true)
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(mapRight, 1_000).sent)
        assertEquals("bounce", emptyList<AaCommand>(), joystick.press(mapRight, 1_000 + JoystickMap.BOUNCE_MS - 1).sent)
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), joystick.press(mapRight, 1_400).sent)
    }

    @Test
    fun `with no call the card changes nothing, the page's volume is the music's, and on a call the same side is the call's`() {
        for (event in ringingEvents) assertEquals("$event with no call", sent(false, CallState.IDLE, event), sent(true, CallState.IDLE, event))
        val volumePage = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE), Action.VOLUME_UP)
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), sent(true, CallState.IDLE, mediaRight, volumePage))
        assertEquals("on a call it is the call's volume, not the music's", listOf<AaCommand>(AaCommand.CallVolume(1)), sent(true, CallState.IN_CALL, mediaRight, volumePage))
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
