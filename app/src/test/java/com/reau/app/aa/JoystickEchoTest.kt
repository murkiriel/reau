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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which taps of the joystick are echoed in the title of the dash's gold band, and with which name (P30 Eco): the tap that really does something
 * ([JoystickMap.Press.echo]); never a bounce, a tap ignored or not mapped, a page action of "Nothing", or a tap that sent nothing.
 */
class JoystickEchoTest {
    private val mediaRight = DashButtonEvent.NextTrack(0x09)
    private val mediaLeft = DashButtonEvent.PrevTrack(0x0A)
    private val mediaUp = DashButtonEvent.VolumeUp(0x06)
    private val mediaDown = DashButtonEvent.VolumeDown(0x07)
    private val mediaCenter = DashButtonEvent.PlayPause(0x05)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapLeft = DashButtonEvent.ZoomIn(0x14)
    private val mapDown = DashButtonEvent.Unknown(0x15)
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)
    private val dashBack = DashButtonEvent.JoystickBack(0x12)

    private fun page(mode: Mode, control: Control, action: Action) = JoystickBindings.DEFAULT.with(Slot(mode, control, Gesture.ONCE), action)

    // ── the page's actions ──

    @Test
    fun `a tap of the default page echoes the name of its action`() {
        val m = JoystickMap()
        assertEquals(EchoLabel.WHEEL_NEXT, m.press(mediaRight, 0).echo)
        assertEquals(EchoLabel.WHEEL_PREVIOUS, m.press(mediaLeft, 5_000).echo)
        assertEquals(EchoLabel.HOME, m.press(mediaDown, 10_000).echo)
        assertEquals(EchoLabel.CONFIRM, m.press(mediaCenter, 15_000).echo)
    }

    @Test
    fun `every action the rider can put on a control echoes its own name`() {
        val wanted = mapOf(
            Action.WHEEL_PREVIOUS to EchoLabel.WHEEL_PREVIOUS, Action.WHEEL_NEXT to EchoLabel.WHEEL_NEXT, Action.CONFIRM to EchoLabel.CONFIRM,
            Action.BACK to EchoLabel.BACK, Action.HOME to EchoLabel.HOME, Action.PLAY_PAUSE to EchoLabel.PLAY_PAUSE,
            Action.NEXT_SONG to EchoLabel.NEXT_SONG, Action.PREVIOUS_SONG to EchoLabel.PREVIOUS_SONG, Action.ASSISTANT to EchoLabel.ASSISTANT,
            Action.ZOOM_IN to EchoLabel.ZOOM_IN, Action.ZOOM_OUT to EchoLabel.ZOOM_OUT, Action.OPEN_NAVIGATION to EchoLabel.OPEN_NAVIGATION,
        )
        for ((action, label) in wanted) {
            val m = JoystickMap(page(Mode.MEDIA, Control.UP, action), assistant = true)
            assertEquals("$action", label, m.press(mediaUp, 0).echo)
        }
    }

    @Test
    fun `the new actions echo too, the info ring's switch among them`() {
        assertEquals(EchoLabel.OPEN_NAVIGATION, JoystickMap(page(Mode.MEDIA, Control.CENTER, Action.OPEN_NAVIGATION)).press(mediaCenter, 0).echo)
        assertEquals("the ring's switch sends nothing, but it acts", EchoLabel.INFO_RING, JoystickMap().press(mapDown, 0).echo)
        assertEquals(EchoLabel.ZOOM_OUT, JoystickMap(page(Mode.MAP, Control.RIGHT, Action.ZOOM_OUT)).press(mapRight, 0).echo)
    }

    @Test
    fun `an action of nothing echoes nothing`() {
        val press = JoystickMap().press(mediaUp, 0)
        assertEquals("the default of up is nothing", emptyList<AaCommand>(), press.sent)
        assertNull(press.echo)
        assertNull(JoystickMap(page(Mode.MEDIA, Control.CENTER, Action.NOTHING)).press(mediaCenter, 0).echo)
    }

    @Test
    fun `the assistant with no microphone given sends nothing and echoes nothing`() {
        val without = JoystickMap(page(Mode.MEDIA, Control.UP, Action.ASSISTANT), assistant = false).press(mediaUp, 0)
        assertEquals(emptyList<AaCommand>(), without.sent)
        assertNull(without.echo)
        assertEquals(EchoLabel.ASSISTANT, JoystickMap(page(Mode.MEDIA, Control.UP, Action.ASSISTANT), assistant = true).press(mediaUp, 0).echo)
    }

    // ── what does not echo ──

    @Test
    fun `a bounce echoes nothing`() {
        val m = JoystickMap()
        assertEquals(EchoLabel.WHEEL_NEXT, m.press(mediaRight, 1_000).echo)
        val bounce = m.press(mediaRight, 1_020)
        assertEquals(listOf("bounce, ignored"), bounce.lines)
        assertNull(bounce.echo)
    }

    @Test
    fun `a tap ignored right after a side, a code not mapped and the dash's notice echo nothing`() {
        val m = JoystickMap()
        m.press(mediaRight, 0)
        val ignored = m.press(mediaUp, 100)
        assertEquals(listOf("right after a side, ignored"), ignored.lines)
        assertNull(ignored.echo)
        val unknown = m.press(DashButtonEvent.Unknown(0x77), 10_000)
        assertEquals(listOf("not mapped -> nothing"), unknown.lines)
        assertNull(unknown.echo)
        assertNull(m.press(DashButtonEvent.Unknown(0x0B), 20_000).echo)
    }

    @Test
    fun `the map's centre right after a side only takes the zoom back and echoes nothing`() {
        val m = JoystickMap(page(Mode.MAP, Control.RIGHT, Action.ZOOM_IN))
        assertEquals(EchoLabel.ZOOM_IN, m.press(mapRight, 0).echo)
        val undo = m.press(mapCenter, 120)
        assertEquals(listOf(AaCommand.PanelZoomUndo), undo.sent)
        assertNull(undo.echo)
    }

    @Test
    fun `a media centre that takes a side's step back echoes the centre's own action`() {
        val m = JoystickMap()
        m.press(mediaRight, 0)
        val press = m.press(mediaCenter, 150)
        assertEquals(listOf(AaCommand.Scroll(-1), AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER)), press.sent)
        assertEquals(EchoLabel.CONFIRM, press.echo)
    }

    // ── back and home ──

    @Test
    fun `the dash's back echoes back, and twice within 700 ms echoes the apps menu`() {
        val m = JoystickMap()
        assertEquals(EchoLabel.BACK, m.press(dashBack, 10_000).echo)
        assertEquals(EchoLabel.HOME, m.press(dashBack, 10_500).echo)
        assertEquals("a pair is used once", EchoLabel.BACK, m.press(dashBack, 10_900).echo)
        assertEquals("far from the last", EchoLabel.BACK, m.press(dashBack, 30_000).echo)
    }

    // ── the calls ──

    @Test
    fun `while the phone rings the left echoes answer and the right decline`() {
        assertEquals(EchoLabel.ANSWER_CALL, JoystickMap(callState = { CallState.RINGING }).press(mediaLeft, 0).echo)
        assertEquals(EchoLabel.REJECT_CALL, JoystickMap(callState = { CallState.RINGING }).press(mediaRight, 0).echo)
        assertEquals(EchoLabel.ANSWER_CALL, JoystickMap(callState = { CallState.RINGING }).press(mapLeft, 0).echo)
        assertEquals(EchoLabel.REJECT_CALL, JoystickMap(callState = { CallState.RINGING }).press(mapRight, 0).echo)
    }

    @Test
    fun `the dash's own call screen echoes the same`() {
        assertEquals(EchoLabel.ANSWER_CALL, JoystickMap(callState = { CallState.RINGING }).press(DashButtonEvent.Unknown(0x02), 0).echo)
        assertEquals(EchoLabel.REJECT_CALL, JoystickMap(callState = { CallState.RINGING }).press(DashButtonEvent.Unknown(0x01), 0).echo)
    }

    @Test
    fun `on a call the right and the centre echo hang up, the left and the volume nothing`() {
        fun call() = JoystickMap(callState = { CallState.IN_CALL })
        assertEquals(EchoLabel.END_CALL, call().press(mediaRight, 0).echo)
        assertEquals(EchoLabel.END_CALL, call().press(DashButtonEvent.Unknown(0x16), 0).echo)
        val left = call().press(mediaLeft, 0)
        assertEquals(listOf("left: on a call, nothing"), left.lines)
        assertNull(left.echo)
        assertNull(call().press(DashButtonEvent.Unknown(0x03), 0).echo)
    }

    @Test
    fun `while the phone rings the media centre declines and echoes it`() {
        assertEquals(EchoLabel.REJECT_CALL, JoystickMap(callState = { CallState.RINGING }).press(mediaCenter, 0).echo)
    }

    @Test
    fun `the same tap again right after a call was handled echoes nothing`() {
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertEquals(EchoLabel.ANSWER_CALL, m.press(mediaLeft, 0).echo)
        state = CallState.IN_CALL
        val again = m.press(mediaLeft, 500)
        assertNull(again.echo)
    }
}
