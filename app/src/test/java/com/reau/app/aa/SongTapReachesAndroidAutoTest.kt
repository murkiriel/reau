package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-song and previous-song actions (P32 Joy) reach Android Auto as the media keys on the first tap, and nothing in the way swallows them but the
 * rules that always held (a bounce, a stray side before the centre, a call). In particular the legend that the first tap of a mode raises is only
 * attached to the tap ([JoystickMap.Press.legend]): the tap's action still goes.
 */
class SongTapReachesAndroidAutoTest {
    private val next = AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_NEXT)
    private val previous = AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_PREVIOUS)
    private val mediaUp = DashButtonEvent.VolumeUp(0x06)
    private val mediaDown = DashButtonEvent.VolumeDown(0x07)
    private val mediaLeft = DashButtonEvent.PrevTrack(0x0A)
    private val mediaRight = DashButtonEvent.NextTrack(0x09)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)

    private fun page(vararg picks: Pair<Slot, Action>) = picks.fold(JoystickBindings.DEFAULT) { p, (slot, action) -> p.with(slot, action) }
    private fun slot(mode: Mode, control: Control) = Slot(mode, control, Gesture.ONCE)

    @Test
    fun `the first tap of the projection raises the legend and still sends the song key`() {
        val map = JoystickMap(page(slot(Mode.MEDIA, Control.UP) to Action.NEXT_SONG, slot(Mode.MEDIA, Control.DOWN) to Action.PREVIOUS_SONG))
        val first = map.press(mediaUp, 0)
        assertEquals("the legend is due on this very tap", Mode.MEDIA, first.legend)
        assertEquals("and the tap is not swallowed by it", listOf<AaCommand>(next), first.sent)
        assertEquals(EchoLabel.NEXT_SONG, first.echo)
        val second = map.press(mediaDown, 5_000)
        assertNull(second.legend)
        assertEquals(listOf<AaCommand>(previous), second.sent)
    }

    @Test
    fun `the first tap after a change of mode raises the legend and still sends the song key`() {
        val map = JoystickMap(page(slot(Mode.MEDIA, Control.UP) to Action.NEXT_SONG, slot(Mode.MAP, Control.RIGHT) to Action.PREVIOUS_SONG))
        map.press(mapRight, 0)
        val change = map.press(mediaUp, 5_000)
        assertEquals(Mode.MEDIA, change.legend)
        assertEquals(listOf<AaCommand>(next), change.sent)
        val back = map.press(mapRight, 10_000)
        assertEquals(Mode.MAP, back.legend)
        assertEquals(listOf<AaCommand>(previous), back.sent)
    }

    @Test
    fun `a song key on a side goes at once with the music mode off`() {
        val map = JoystickMap(page(slot(Mode.MEDIA, Control.RIGHT) to Action.NEXT_SONG, slot(Mode.MEDIA, Control.LEFT) to Action.PREVIOUS_SONG))
        assertEquals(listOf<AaCommand>(next), map.press(mediaRight, 0).sent)
        assertEquals(listOf<AaCommand>(previous), map.press(mediaLeft, 5_000).sent)
    }

    @Test
    fun `with the music mode on the sides are the song's keys by themselves, a quarter of a second after the tap`() {
        val map = JoystickMap()
        map.musicActive = { true }
        val tap = map.press(mediaRight, 0)
        assertEquals("held for the dash's ghost, not lost", emptyList<AaCommand>(), tap.sent)
        assertEquals(EchoLabel.NEXT_SONG, tap.echo)
        assertNull(map.release(JoystickMap.HELD_SIDE_MS - 1))
        assertEquals(listOf<AaCommand>(next), map.release(JoystickMap.HELD_SIDE_MS)?.sent)
    }

    @Test
    fun `what does swallow a tap, a bounce, the centre right after a side, a call`() {
        val map = JoystickMap(page(slot(Mode.MEDIA, Control.UP) to Action.NEXT_SONG))
        assertEquals(listOf<AaCommand>(next), map.press(mediaUp, 0).sent)
        assertTrue("the same code within 60 ms is a bounce", map.press(mediaUp, 30).sent.isEmpty())
        assertEquals(listOf<AaCommand>(next), map.press(mediaUp, 5_000).sent)
        // An up within 150 ms after a side is the side's own gesture, as it always was.
        map.press(mediaLeft, 10_000)
        assertTrue(map.press(mediaUp, 10_100).sent.isEmpty())
        assertEquals("later it is a tap", listOf<AaCommand>(next), map.press(mediaUp, 15_000).sent)
        val calling = JoystickMap(page(slot(Mode.MEDIA, Control.UP) to Action.NEXT_SONG), callState = { CallState.IN_CALL })
        assertEquals("up goes through the page on a call too", listOf<AaCommand>(next), calling.press(mediaUp, 0).sent)
        val ringing = JoystickMap(page(slot(Mode.MEDIA, Control.RIGHT) to Action.NEXT_SONG), callState = { CallState.RINGING })
        assertTrue("a side while the phone rings is the call's", ringing.press(mediaRight, 0).sent.none { it == next })
    }

    @Test
    fun `the song keys are the ones the sending tells apart, and no others`() {
        assertTrue(JoystickMap.isSongKey(ServiceDiscoveryResponse.KEY_MEDIA_NEXT))
        assertTrue(JoystickMap.isSongKey(ServiceDiscoveryResponse.KEY_MEDIA_PREVIOUS))
        for (key in listOf(
            ServiceDiscoveryResponse.KEY_MEDIA_PLAY_PAUSE, ServiceDiscoveryResponse.KEY_MEDIA, ServiceDiscoveryResponse.KEY_ENTER,
            ServiceDiscoveryResponse.KEY_HOME, ServiceDiscoveryResponse.KEY_UP, ServiceDiscoveryResponse.KEY_DOWN,
        )) {
            assertFalse("$key", JoystickMap.isSongKey(key))
        }
        assertNotNull(Action.NEXT_SONG.command(false))
        assertEquals(next, Action.NEXT_SONG.command(false))
        assertEquals(previous, Action.PREVIOUS_SONG.command(false))
    }

    @Test
    fun `only the next and previous song labels hold their name in the title for the new song`() {
        for (label in EchoLabel.entries) {
            assertEquals("$label", label == EchoLabel.NEXT_SONG || label == EchoLabel.PREVIOUS_SONG, label.changesSong)
        }
    }
}
