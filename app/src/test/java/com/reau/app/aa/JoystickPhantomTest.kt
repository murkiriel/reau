package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dash's phantom code next to a side, with the delays the rides showed. In the map mode a down came 104 ms after a side (0x13 then
 * 0x15). In the media mode a side came 111 to 188 ms before the centre: it moved the focus one item and the centre then confirmed the wrong
 * one, so the centre now takes the side's step back first.
 */
class JoystickPhantomTest {
    private val right = DashButtonEvent.NextTrack(0x09)
    private val left = DashButtonEvent.PrevTrack(0x0A)
    private val down = DashButtonEvent.VolumeDown(0x07)
    private val center = DashButtonEvent.PlayPause(0x05)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapDown = DashButtonEvent.Unknown(0x15)
    private val joystickIn = DashButtonEvent.JoystickIn(0x18)

    private val confirm = AaCommand.Key(23)
    private val home = AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME)

    /** The map's down on Home, so that a down which goes through sends something to tell it from one held back. */
    private fun mapWithDownHome() = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MAP, Control.DOWN, Gesture.ONCE), Action.HOME))

    // ── the map mode: a down right after a side ──

    @Test
    fun `a map down 104 ms after a side is the same gesture, ignored`() {
        val m = mapWithDownHome()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(mapRight, 0).sent)
        val ghost = m.press(mapDown, 104)
        assertTrue(ghost.sent.isEmpty())
        assertEquals(listOf("right after a side, ignored"), ghost.lines)
    }

    @Test
    fun `the map down is held back up to 149 ms after a side, and counts from 150`() {
        for ((gap, held) in listOf(149L to true, 150L to false, 151L to false)) {
            val m = mapWithDownHome()
            m.press(mapRight, 1_000)
            assertEquals("down $gap ms after a side", if (held) emptyList<AaCommand>() else listOf(home), m.press(mapDown, 1_000 + gap).sent)
        }
    }

    @Test
    fun `a map down long after a side, or with no side, is a tap`() {
        val m = mapWithDownHome()
        assertEquals(listOf(home), m.press(mapDown, 0).sent)
        m.press(mapRight, 2_000)
        assertEquals(listOf(home), m.press(mapDown, 2_400).sent)
    }

    @Test
    fun `the media mode's down is no phantom, it may come right after a side on purpose`() {
        val m = JoystickMap()
        m.press(right, 0)
        assertEquals("123 ms, as in the rides", listOf(AaCommand.Key(3)), m.press(down, 123).sent)
        m.press(right, 5_000)
        assertEquals("26 ms", listOf(AaCommand.Key(3)), m.press(down, 5_026).sent)
    }

    // ── the media mode: a centre after a side takes the side's step back ──

    @Test
    fun `a media centre 111 ms and 188 ms after a side puts the focus back and confirms`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        val first = m.press(center, 111)
        assertEquals(listOf(AaCommand.Scroll(-1), confirm), first.sent)
        assertEquals(
            listOf("media center right after a side: its step of the focus is taken back", "media center once -> Key(keycode=23)"),
            first.lines,
        )
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(left, 5_000).sent)
        assertEquals("a left is taken back with a step the other way", listOf(AaCommand.Scroll(1), confirm), m.press(center, 5_188).sent)
    }

    @Test
    fun `the media centre is held back up to 99 ms after a side, and from 100 to 250 ms it takes the step back`() {
        for ((gap, expected) in listOf(
            // Up to 99 ms the centre is the side's own gesture (as before); from 100 ms the side was the ghost, and the centre is the rider's.
            30L to emptyList<AaCommand>(),
            99L to emptyList<AaCommand>(),
            100L to listOf(AaCommand.Scroll(-1), confirm),
            149L to listOf(AaCommand.Scroll(-1), confirm),
            150L to listOf(AaCommand.Scroll(-1), confirm),
            151L to listOf(AaCommand.Scroll(-1), confirm),
            250L to listOf(AaCommand.Scroll(-1), confirm),
            251L to listOf(confirm),
        )) {
            val m = JoystickMap()
            m.press(right, 1_000)
            assertEquals("centre $gap ms after a side", expected, m.press(center, 1_000 + gap).sent)
        }
    }

    @Test
    fun `a media centre 400 ms after a side, a tap on purpose, goes intact`() {
        val m = JoystickMap()
        m.press(right, 0)
        val press = m.press(center, 400)
        assertEquals(listOf(confirm), press.sent)
        assertEquals(listOf("media center once -> Key(keycode=23)"), press.lines)
    }

    @Test
    fun `the step is taken back once per side`() {
        val m = JoystickMap()
        m.press(right, 0)
        assertEquals(listOf(AaCommand.Scroll(-1), confirm), m.press(center, 120).sent)
        assertEquals("a second centre, still within 250 ms of the side, has no side behind it", listOf(confirm), m.press(center, 200).sent)
    }

    @Test
    fun `a side that moved no focus leaves nothing to take back`() {
        val m = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE), Action.NEXT_SONG))
        assertEquals(listOf(AaCommand.Key(87)), m.press(right, 0).sent)
        assertEquals(listOf(confirm), m.press(center, 200).sent)
    }

    @Test
    fun `a map side does not make the media centre step back, and a media side does not make the map centre swallow`() {
        val m = JoystickMap()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(mapRight, 0).sent)
        assertEquals("a map side moved the focus, but the centre is the media's", listOf(confirm), m.press(center, 200).sent)
        m.press(right, 5_000)
        val mapCenter = m.press(joystickIn, 5_200)
        assertTrue(mapCenter.sent.isEmpty())
        assertEquals(listOf("map center once -> nothing"), mapCenter.lines)
    }
}
