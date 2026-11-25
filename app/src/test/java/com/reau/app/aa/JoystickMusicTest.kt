package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick's music mode at the buttons: with Spotify in front and playing, the media mode's left, right and centre are previous song, next
 * song and play/pause; after a pause they stay so for 5 s; up and down and the map mode never change. A side goes 250 ms after the tap and is
 * dropped if the centre comes meanwhile (the dash's ghost). The clock is the test's: every press and every release gives its time.
 */
class JoystickMusicTest {
    private val right = DashButtonEvent.NextTrack(0x09)
    private val left = DashButtonEvent.PrevTrack(0x0A)
    private val up = DashButtonEvent.VolumeUp(0x06)
    private val down = DashButtonEvent.VolumeDown(0x07)
    private val center = DashButtonEvent.PlayPause(0x05)
    private val mapRight = DashButtonEvent.ZoomOut(0x13)
    private val mapLeft = DashButtonEvent.ZoomIn(0x14)
    private val mapCenter = DashButtonEvent.JoystickIn(0x18)

    private val next = AaCommand.Key(87)
    private val previous = AaCommand.Key(88)
    private val playPause = AaCommand.Key(85)

    private val spotify = MediaNow(playing = true, source = "Spotify")
    private val music = MusicMode()

    private fun map(bindings: JoystickBindings = JoystickBindings.DEFAULT, call: CallState = CallState.IDLE) =
        JoystickMap(bindings, callState = { call }).also { it.musicActive = music::active }

    /** Spotify in front, playing, since [nowMs]. */
    private fun playing(nowMs: Long = 0) = music.update(AaScreen.APPS, AaApp.SPOTIFY, spotify, nowMs)

    @Test
    fun `without the music mode nothing changes`() {
        val m = map()
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 0).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(left, 1_000).sent)
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 2_000).sent)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `playing, the centre is play-pause at once`() {
        playing()
        val m = map()
        val press = m.press(center, 10_000)
        assertEquals(listOf(playPause), press.sent)
        assertEquals(listOf("media center once -> music mode, play_pause"), press.lines)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `playing, a side is held and goes 250 ms after the tap, not before`() {
        playing()
        val m = map()
        val press = m.press(right, 10_000)
        assertTrue("the tap sends nothing", press.sent.isEmpty())
        assertEquals(10_250L, m.heldDueMs())
        assertNull("at 249 ms it is not due", m.release(10_249))
        assertEquals(10_250L, m.heldDueMs())
        assertEquals(listOf(next), m.release(10_250)?.sent)
        assertNull("sent once", m.release(10_300))
        assertNull(m.heldDueMs())
    }

    @Test
    fun `the left is the previous song`() {
        playing()
        val m = map()
        m.press(left, 5_000)
        assertEquals(listOf(previous), m.release(5_250)?.sent)
    }

    @Test
    fun `the page's choices for the sides and the centre do not count in the music mode, up and down do`() {
        playing()
        val page = JoystickBindings.DEFAULT
            .with(Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE), Action.HOME)
            .with(Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE), Action.BACK)
            .with(Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE), Action.ASSISTANT)
            .with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Action.HOME)
            .with(Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE), Action.BACK)
        val m = JoystickMap(page, assistant = true).also { it.musicActive = music::active }
        assertEquals(listOf(playPause), m.press(center, 1_000).sent)
        m.press(left, 2_000)
        assertEquals(listOf(previous), m.release(2_250)?.sent)
        m.press(right, 3_000)
        assertEquals(listOf(next), m.release(3_250)?.sent)
        assertEquals("up is the page's", listOf(AaCommand.Key(3)), m.press(up, 4_000).sent)
        assertEquals("down is the page's", listOf(AaCommand.Key(4)), m.press(down, 5_000).sent)
    }

    @Test
    fun `up and down are as before in the music mode`() {
        playing()
        val m = map()
        assertEquals(emptyList<AaCommand>(), m.press(up, 1_000).sent)
        assertEquals(listOf(AaCommand.Key(3)), m.press(down, 2_000).sent)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `the map mode never changes`() {
        playing()
        val m = map(JoystickBindings.DEFAULT.with(Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE), Action.CONFIRM))
        assertEquals(listOf(AaCommand.Key(23)), m.press(mapRight, 1_000).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(mapLeft, 2_000).sent)
        assertNull(m.heldDueMs())
        assertEquals("the map's centre is the dash's own", emptyList<AaCommand>(), m.press(mapCenter, 5_000).sent)
    }

    @Test
    fun `a side and the centre 120 ms later is the centre alone`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        val press = m.press(center, 10_120)
        assertEquals(listOf(playPause), press.sent)
        assertTrue(press.lines.toString(), press.lines.any { it.contains("right dropped as the dash's ghost") && it.contains("120 ms") })
        assertEquals("the first line is the centre's own", "media center once -> music mode, play_pause", press.lines.first())
        assertNull("nothing is left to send", m.heldDueMs())
        assertNull(m.release(11_000))
    }

    @Test
    fun `the ghost's whole range, 111 to 249 ms, is dropped`() {
        playing()
        for (gap in listOf(111L, 150L, 202L, 249L)) {
            val m = map()
            m.press(right, 100_000)
            assertEquals("gap $gap", listOf(playPause), m.press(center, 100_000 + gap).sent)
            assertNull("gap $gap", m.release(200_000))
        }
    }

    @Test
    fun `a centre 250 ms after a side comes after it, both go`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        assertEquals(listOf(next, playPause), m.press(center, 10_250).sent)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `a centre under 100 ms after a side is still that gesture's, ignored as before, and the side goes`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        val press = m.press(center, 10_099)
        assertTrue(press.sent.isEmpty())
        assertEquals(listOf("right after a side, ignored"), press.lines)
        assertEquals(listOf(next), m.release(10_250)?.sent)
    }

    @Test
    fun `a side that is due when the next press comes goes first, in order`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        // The tick did not run: the next press finds the side due.
        val press = m.press(down, 10_400)
        assertEquals(listOf(next, AaCommand.Key(3)), press.sent)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `a second side while one is held sends the first at once and holds the new one`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        val press = m.press(left, 10_200)
        assertEquals(listOf(next), press.sent)
        assertEquals(10_450L, m.heldDueMs())
        assertEquals(listOf(previous), m.release(10_450)?.sent)
    }

    @Test
    fun `two taps on the same side are two songs`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        assertEquals(listOf(next), m.press(right, 10_300).sent)
        assertEquals(listOf(next), m.release(10_550)?.sent)
    }

    @Test
    fun `the held side is not sent if the centre dropped it, and the next side after it is held on its own`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        m.press(center, 10_150)
        m.press(right, 20_000)
        assertEquals(20_250L, m.heldDueMs())
        assertEquals(listOf(next), m.release(20_250)?.sent)
    }

    @Test
    fun `paused 4 point 9 s ago the buttons are still the music's, at 5 s they are the page's`() {
        playing()
        music.update(AaScreen.APPS, AaApp.SPOTIFY, spotify.copy(playing = false), 100_000)
        val m = map()
        assertEquals(listOf(playPause), m.press(center, 104_900).sent)
        assertEquals("the page's centre, 5.0 s after", listOf(AaCommand.Key(23)), m.press(center, 105_000).sent)
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 106_000).sent)
        assertNull(m.heldDueMs())
    }

    @Test
    fun `playing again within the 5 s keeps the music mode`() {
        playing()
        music.update(AaScreen.APPS, AaApp.SPOTIFY, spotify.copy(playing = false), 100_000)
        music.update(AaScreen.APPS, AaApp.SPOTIFY, spotify, 103_000)
        val m = map()
        assertEquals(listOf(playPause), m.press(center, 120_000).sent)
    }

    @Test
    fun `Spotify leaving the screen is the page's at once`() {
        playing()
        music.update(AaScreen.APPS, AaApp.OTHER, spotify, 100_000)
        val m = map()
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 100_001).sent)
        assertEquals(listOf(AaCommand.Scroll(1)), m.press(right, 101_000).sent)
    }

    @Test
    fun `another app in front with Spotify playing behind is the page's`() {
        music.update(AaScreen.APPS, AaApp.OTHER, spotify, 0)
        val m = map()
        assertEquals(listOf(AaCommand.Key(23)), m.press(center, 1_000).sent)
        assertEquals(listOf(AaCommand.Scroll(-1)), m.press(left, 2_000).sent)
    }

    @Test
    fun `a ringing phone or a call comes before the music mode`() {
        playing()
        val ringing = map(call = CallState.RINGING)
        assertEquals(listOf(AaCommand.AnswerCall), ringing.press(left, 1_000).sent)
        assertEquals(listOf(AaCommand.EndCall), ringing.press(center, 2_000).sent)
        assertNull(ringing.heldDueMs())
        val inCall = map(call = CallState.IN_CALL)
        assertEquals(listOf(AaCommand.EndCall), inCall.press(right, 1_000).sent)
        assertTrue("the centre on a call is not play-pause", playPause !in inCall.press(center, 5_000).sent)
        assertNull(inCall.heldDueMs())
    }

    @Test
    fun `the bounce of the dash is not a second song`() {
        playing()
        val m = map()
        m.press(right, 10_000)
        val bounce = m.press(right, 10_030)
        assertTrue(bounce.sent.isEmpty())
        assertEquals(listOf(next), m.release(10_250)?.sent)
        assertNull(m.release(10_300))
    }

    @Test
    fun `the tap's line says the song key, the 250 ms and the way out`() {
        playing()
        val line = map().press(right, 0).lines.single()
        assertEquals("media right once -> music mode, next_song, after 250 ms unless the centre comes", line)
    }

    @Test
    fun `the release's line names the side and the delay`() {
        playing()
        val m = map()
        m.press(left, 0)
        assertEquals(listOf("music mode: left sent 260 ms after the tap"), m.release(260)?.lines)
    }

    @Test
    fun `the held time is the longest a centre still takes a side for the ghost`() {
        assertEquals(CenterUndo.UNDO_MS, JoystickMap.HELD_SIDE_MS)
        assertEquals(250L, JoystickMap.HELD_SIDE_MS)
    }
}
