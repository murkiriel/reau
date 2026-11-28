package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.ActionLegend
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the joystick's legend is due and what it says (P30 Eco): at the first tap of a dash mode that is not the last tap's mode, the first
 * of the projection included, and by the page the rider set. The dash does not say its mode; the code of the tap does.
 */
class JoystickLegendTest {
    private val mediaRight = DashButtonEvent.NextTrack(0x09)
    private val mediaCenter = DashButtonEvent.PlayPause(0x05)
    private val mediaDown = DashButtonEvent.VolumeDown(0x07)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapLeft = DashButtonEvent.ZoomIn(0x14)
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)
    private val mapDown = DashButtonEvent.Unknown(0x15)
    private val dashBack = DashButtonEvent.JoystickBack(0x12)

    // ── when it is due ──

    @Test
    fun `the first tap of the projection is the first of its mode, a media tap or a map tap`() {
        assertEquals(Mode.MEDIA, JoystickMap().press(mediaRight, 0).legend)
        assertEquals(Mode.MAP, JoystickMap().press(mapRight, 0).legend)
        assertEquals("the map's up is the dash's own back", Mode.MAP, JoystickMap().press(dashBack, 0).legend)
    }

    @Test
    fun `more taps of the same mode bring no legend`() {
        val m = JoystickMap()
        assertEquals(Mode.MEDIA, m.press(mediaRight, 0).legend)
        assertNull(m.press(mediaDown, 5_000).legend)
        assertNull(m.press(mediaCenter, 10_000).legend)
        assertNull(m.press(mediaRight, 15_000).legend)
    }

    @Test
    fun `a tap of the other mode brings its legend, and the way back brings the first one's again`() {
        val m = JoystickMap()
        assertEquals(Mode.MEDIA, m.press(mediaRight, 0).legend)
        assertEquals(Mode.MAP, m.press(mapRight, 5_000).legend)
        assertNull(m.press(mapLeft, 10_000).legend)
        assertEquals(Mode.MEDIA, m.press(mediaDown, 15_000).legend)
        assertEquals(Mode.MAP, m.press(dashBack, 20_000).legend)
    }

    @Test
    fun `the map's centre is a map tap, and the media tap after it opens the media legend`() {
        val m = JoystickMap()
        assertEquals(Mode.MAP, m.press(mapRight, 0).legend)
        assertNull(m.press(mapCenter, 3_000).legend)
        assertEquals(Mode.MEDIA, m.press(mediaRight, 6_000).legend)
    }

    @Test
    fun `a tap that is a bounce or has no mode neither brings a legend nor changes the mode`() {
        val m = JoystickMap()
        assertNull("the dash's notice is no tap", m.press(DashButtonEvent.Unknown(0x0B), 0).legend)
        assertNull("a code nobody knows", m.press(DashButtonEvent.Unknown(0x77), 100).legend)
        assertEquals("the first real tap is still the first", Mode.MEDIA, m.press(mediaRight, 5_000).legend)
        assertNull("a bounce", m.press(mediaRight, 5_020).legend)
        assertNull(m.press(DashButtonEvent.Unknown(0x77), 8_000).legend)
        assertNull("the unknown code did not take the mode away", m.press(mediaDown, 10_000).legend)
    }

    @Test
    fun `a tap the filter ignores still tells the mode`() {
        val m = JoystickMap()
        m.press(mediaRight, 0)
        assertEquals("a map tap right after a media one: the mode changed", Mode.MAP, m.press(mapDown, 50).legend)
    }

    @Test
    fun `while the phone rings or a call goes on no legend is due, but the mode is remembered`() {
        var state = CallState.RINGING
        val m = JoystickMap(callState = { state })
        assertNull("the sides are the call's", m.press(mediaRight, 0).legend)
        state = CallState.IDLE
        assertNull("the same mode as the tap in the call", m.press(mediaDown, 10_000).legend)
        assertEquals(Mode.MAP, m.press(mapRight, 20_000).legend)
    }

    @Test
    fun `a new map for a new projection starts with no mode again`() {
        val first = JoystickMap()
        first.press(mediaRight, 0)
        assertEquals(Mode.MEDIA, JoystickMap().press(mediaRight, 0).legend)
    }

    // ── what it says ──

    @Test
    fun `the media legend is the rider's page, in the order up, down, left, right, centre`() {
        assertEquals(listOf(Control.UP, Control.DOWN, Control.LEFT, Control.RIGHT, Control.CENTER), ActionLegend.ORDER)
        assertEquals(
            listOf(
                Control.UP to null, Control.DOWN to EchoLabel.HOME, Control.LEFT to EchoLabel.WHEEL_PREVIOUS,
                Control.RIGHT to EchoLabel.WHEEL_NEXT, Control.CENTER to EchoLabel.CONFIRM,
            ),
            JoystickMap().legend(Mode.MEDIA),
        )
    }

    @Test
    fun `the map legend has the dash's own up and centre, the ring's switch below and the page's sides`() {
        assertEquals(
            listOf(
                Control.UP to EchoLabel.EXIT_NAVIGATION, Control.DOWN to EchoLabel.INFO_RING, Control.LEFT to EchoLabel.WHEEL_PREVIOUS,
                Control.RIGHT to EchoLabel.WHEEL_NEXT, Control.CENTER to EchoLabel.MEDIA_MODE,
            ),
            JoystickMap().legend(Mode.MAP),
        )
    }

    @Test
    fun `the legend follows the page the rider set, also one changed while the projection runs`() {
        val m = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.PLAY_PAUSE))
        assertEquals(EchoLabel.PLAY_PAUSE, m.legend(Mode.MEDIA).first { it.first == Control.UP }.second)
        m.bindings = m.bindings.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.NEXT_SONG)
            .with(Slot(Mode.MAP, Control.DOWN, Gesture.ONCE), Action.ZOOM_IN)
            .with(Slot(Mode.MAP, Control.LEFT, Gesture.ONCE), Action.NOTHING)
        assertEquals(EchoLabel.NEXT_SONG, m.legend(Mode.MEDIA).first { it.first == Control.UP }.second)
        assertEquals(EchoLabel.ZOOM_IN, m.legend(Mode.MAP).first { it.first == Control.DOWN }.second)
        assertNull("nothing on the left of the map", m.legend(Mode.MAP).first { it.first == Control.LEFT }.second)
    }

    @Test
    fun `the legend does not promise an assistant that has no microphone`() {
        val page = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.ASSISTANT)
        assertNull(JoystickMap(page, assistant = false).legend(Mode.MEDIA).first { it.first == Control.UP }.second)
        assertEquals(EchoLabel.ASSISTANT, JoystickMap(page, assistant = true).legend(Mode.MEDIA).first { it.first == Control.UP }.second)
    }

    @Test
    fun `the legend says what a tap does, the same name the echo gives it`() {
        val m = JoystickMap(JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE), Action.SWAP_MAP_MUSIC))
        val legendName = m.legend(Mode.MEDIA).first { it.first == Control.CENTER }.second
        assertEquals(legendName, m.press(mediaCenter, 0).echo)
    }
}
