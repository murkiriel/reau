package com.reau.app.aa

import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import com.reau.app.video.ScreenNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map mode's centre that also opens the music app ([JoystickMap.mapCenterOpensMusic], the rider's switch, off by default): the key of
 * [JoystickBindings.Action.OPEN_MUSIC] goes with the tap that puts the dash in its media mode, with the music's title; not with the music already in front,
 * a bounce, a ringing or going-on call, and never with the switch off. The clock is the test's.
 */
class JoystickMapCenterOpensMusicTest {
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mediaCenter = DashButtonEvent.PlayPause(0x05)

    private val openMusic = AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA)

    /** The page with the zoom out on the map mode's right, so that a side before the centre sends a zoom step to take back. */
    private val zoomPage = JoystickBindings.DEFAULT.with(
        JoystickBindings.Slot(JoystickBindings.Mode.MAP, JoystickBindings.Control.RIGHT, JoystickBindings.Gesture.ONCE), JoystickBindings.Action.ZOOM_OUT,
    )

    private fun map(on: Boolean = true, inFront: Boolean = false, call: CallState = CallState.IDLE, assistant: Boolean = false) =
        JoystickMap(zoomPage, assistant = assistant, callState = { call }).also {
            it.mapCenterOpensMusic = on
            it.musicInFront = { inFront }
        }

    @Test
    fun `off by default, the map centre sends nothing and says nothing, as it always did`() {
        val plain = JoystickMap()
        assertFalse(plain.mapCenterOpensMusic)
        assertFalse(plain.musicInFront())
        val press = plain.press(mapCenter, 1_000)
        assertTrue(press.sent.isEmpty())
        assertEquals(listOf("map center once -> nothing"), press.lines)
        assertNull(press.echo)
        assertEquals(press, map(on = false).press(mapCenter, 1_000))
    }

    @Test
    fun `on, the map centre opens the music and the title says it`() {
        val press = map().press(mapCenter, 1_000)
        assertEquals(listOf(openMusic), press.sent)
        assertEquals(EchoLabel.SWAP_TO_MUSIC, press.echo)
        assertEquals(listOf("map center once -> nothing", "map center -> media mode + open music"), press.lines)
    }

    @Test
    fun `the first tap of the projection still asks for the map mode's legend`() {
        assertEquals(JoystickBindings.Mode.MAP, map().press(mapCenter, 1_000).legend)
    }

    @Test
    fun `with the music already in front nothing is sent and the title stays as it was`() {
        val press = map(inFront = true).press(mapCenter, 1_000)
        assertTrue(press.sent.isEmpty())
        assertNull(press.echo)
        assertEquals(listOf("map center once -> nothing", "map center -> media mode, music already in front, nothing"), press.lines)
    }

    @Test
    fun `the switch and the screen are read at each tap`() {
        val m = map(on = false)
        assertTrue(m.press(mapCenter, 1_000).sent.isEmpty())
        m.mapCenterOpensMusic = true
        assertEquals(listOf(openMusic), m.press(mapCenter, 5_000).sent)
        var front = true
        m.musicInFront = { front }
        assertTrue(m.press(mapCenter, 10_000).sent.isEmpty())
        front = false
        assertEquals(listOf(openMusic), m.press(mapCenter, 15_000).sent)
        m.mapCenterOpensMusic = false
        assertTrue(m.press(mapCenter, 20_000).sent.isEmpty())
    }

    @Test
    fun `a bounce of the centre sends nothing more`() {
        val m = map()
        assertEquals(listOf(openMusic), m.press(mapCenter, 1_000).sent)
        val bounce = m.press(mapCenter, 1_020)
        assertTrue(bounce.sent.isEmpty())
        assertNull(bounce.echo)
        assertEquals(listOf("bounce, ignored"), bounce.lines)
    }

    @Test
    fun `while the phone rings or a call goes on the centre is the call's and opens nothing`() {
        for (call in listOf(CallState.RINGING, CallState.IN_CALL)) {
            val press = map(call = call).press(mapCenter, 1_000)
            assertFalse("$call", openMusic in press.sent)
            assertTrue("$call", press.lines.none { "open music" in it })
            assertTrue("$call", press.echo != EchoLabel.SWAP_TO_MUSIC)
        }
    }

    @Test
    fun `a centre right after a media side is the side's own gesture and opens nothing`() {
        val m = map()
        m.press(DashButtonEvent.NextTrack(0x09), 1_000)
        val press = m.press(mapCenter, 1_050)
        assertFalse(openMusic in press.sent)
        assertEquals(listOf("right after a side, ignored"), press.lines)
    }

    @Test
    fun `the dash's centre with a map side just before it takes the side back and opens the music`() {
        val m = map()
        val side = m.press(mapRight, 1_000)
        assertEquals(listOf(AaCommand.PanelZoom(-1)), side.sent)
        val press = m.press(mapCenter, 1_120)
        assertEquals(listOf(AaCommand.PanelZoomUndo, openMusic), press.sent)
        assertEquals(EchoLabel.SWAP_TO_MUSIC, press.echo)
        assertTrue(press.lines.last(), press.lines.last().endsWith("open music"))
    }

    @Test
    fun `that centre with the music in front only takes the side back, and with the switch off the same`() {
        val front = map(inFront = true)
        front.press(mapRight, 1_000)
        assertEquals(listOf(AaCommand.PanelZoomUndo), front.press(mapCenter, 1_120).sent)
        val off = map(on = false)
        off.press(mapRight, 1_000)
        assertEquals(listOf(AaCommand.PanelZoomUndo), off.press(mapCenter, 1_120).sent)
    }

    @Test
    fun `no other control opens the music, the media centre included`() {
        val m = map()
        assertFalse(openMusic in m.press(mediaCenter, 1_000).sent)
        assertFalse(openMusic in m.press(mapRight, 10_000).sent)
        assertFalse(openMusic in m.press(DashButtonEvent.Unknown(0x15), 20_000).sent)
        assertFalse(openMusic in m.press(DashButtonEvent.JoystickBack(0x12), 30_000).sent)
    }

    @Test
    fun `the page does not change what the map centre does`() {
        val reset = JoystickMap(JoystickBindings.DEFAULT).also { it.mapCenterOpensMusic = true }
        assertEquals(listOf(openMusic), reset.press(mapCenter, 1_000).sent)
        val other = JoystickMap(
            JoystickBindings.DEFAULT.with(
                JoystickBindings.Slot(JoystickBindings.Mode.MEDIA, JoystickBindings.Control.UP, JoystickBindings.Gesture.ONCE),
                JoystickBindings.Action.NOTHING,
            ),
        ).also { it.mapCenterOpensMusic = true }
        assertEquals(listOf(openMusic), other.press(mapCenter, 1_000).sent)
    }

    @Test
    fun `only Spotify is the music in front, whatever else is on the screen`() {
        for (screen in ScreenNet.Screen.entries) {
            assertEquals("$screen", screen == ScreenNet.Screen.SPOTIFY, SwapMapMusic.musicInFront(screen))
        }
        assertTrue(SwapMapMusic.musicInFront(SwapMapMusic.seen(AaScreen.APPS, AaApp.SPOTIFY)))
        assertFalse(SwapMapMusic.musicInFront(SwapMapMusic.seen(AaScreen.NAV, AaApp.NONE)))
        assertFalse(SwapMapMusic.musicInFront(SwapMapMusic.seen(AaScreen.APPS, AaApp.OTHER)))
        assertFalse(SwapMapMusic.musicInFront(SwapMapMusic.seen(AaScreen.UNKNOWN, AaApp.UNKNOWN)))
    }
}
