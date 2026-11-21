package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name of the song a tap changed to, in the title of the route card (P32 Joy): "Next song" until the metadata comes, then the song's name for 3 s;
 * the street or "REau" after it; nothing outside the 4 s window, nothing with the echo off or a call on the card. The clocks are the test's own.
 */
class DashSessionSongEchoTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val session = DashSession(scope)
    private var now = 10_000L

    init {
        session.echo = TimedShow(ActionEcho.SHOW_MS, nowMs = { now })
        session.songEcho = SongEcho(nowMs = { now })
    }

    @After
    fun tearDown() = scope.cancel()

    /** The title (05 01) of the route card now, as the dash reads it. */
    private fun title(): String {
        val pkt = session.routeCardNow()
        var i = 17
        while (i + 4 <= pkt.size) {
            val len = ((pkt[i + 2].toInt() and 0xFF) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if ((pkt[i].toInt() and 0xFF) == 5 && (pkt[i + 1].toInt() and 0xFF) == 0x01) return String(pkt, i + 4, len - 1, Charsets.ISO_8859_1)
            i += 4 + len
        }
        error("no title in the route card")
    }

    private fun guide(road: String) =
        session.updateNavInfo(0x14, 80, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = road)

    private fun song(name: String) = session.updateNowPlaying(name, "Album", "Artist")

    /** A tap on next song: the key is sent (the song change is asked), and its action's name is the echo. */
    private fun tapNext() {
        session.songChangeAsked()
        session.echoSongAction("Next song")
    }

    @Test
    fun `the name comes in the place of the action's name, for three seconds, and then the title is back`() {
        song("Old Song")
        tapNext()
        assertEquals("Next song", title())
        now += 600
        song("Blue Monday")
        assertEquals("Blue Monday", title())
        now += 2_999
        assertEquals("Blue Monday", title())
        now += 1
        assertEquals("REau", title())
    }

    @Test
    fun `the action's name is held while the metadata is awaited, and the name takes its place late too`() {
        song("Old Song")
        tapNext()
        now += 1_500
        assertEquals("past the usual 1.5 s the action's name is still the title", "Next song", title())
        now += 1_000
        assertEquals("Next song", title())
        song("Blue Monday")
        assertEquals("a name that comes at 2.5 s, inside the 4 s, is shown", "Blue Monday", title())
        now += 3_000
        assertEquals("REau", title())
    }

    @Test
    fun `with no name the held action's name goes away at the end of the 4 s, not before`() {
        song("Old Song")
        tapNext()
        now += 3_999
        assertEquals("Next song", title())
        now += 1
        assertEquals("REau", title())
    }

    @Test
    fun `the held name goes back to the street while guiding when no name comes`() {
        guide("Rua das Flores")
        song("Old Song")
        tapNext()
        now += 3_000
        assertEquals("Next song", title())
        now += 1_000
        assertEquals("Rua das Flores", title())
    }

    @Test
    fun `a name that comes after the window never takes the title`() {
        song("Old Song")
        tapNext()
        now += 4_000
        song("Late Song")
        assertEquals("REau", title())
    }

    @Test
    fun `the music mode's side, sent 250 ms after its tap, holds the name already in the title to the end of the wait`() {
        song("Old Song")
        session.echoSongAction("Next song")
        now += 250
        session.songChangeAsked()
        now += 3_999
        assertEquals("4 s after the key was sent, less a millisecond", "Next song", title())
        now += 1
        assertEquals("REau", title())
    }

    @Test
    fun `another action's name is not held by a song wait`() {
        song("Old Song")
        tapNext()
        now += 100
        session.echoAction("Confirm")
        assertEquals("Confirm", title())
        now += 1_500
        assertEquals("the usual 1.5 s", "REau", title())
    }

    @Test
    fun `an action's name shown before the key does not extend a different echo that replaced it`() {
        song("Old Song")
        session.echoSongAction("Next song")
        session.echoAction("Confirm")
        now += 250
        session.songChangeAsked()
        assertEquals("Confirm", title())
        now += 1_500
        assertEquals("REau", title())
    }

    @Test
    fun `a call keeps the song change from being held`() {
        song("Old Song")
        session.showCaller("Ana")
        session.songChangeAsked()
        session.echoSongAction("Next song")
        now += 1_500
        assertEquals("REau", title())
    }

    @Test
    fun `the same song again does not take the title, and the metadata after the new one does not either`() {
        song("Old Song")
        tapNext()
        now += 200
        song("Old Song")
        assertEquals("Next song", title())
        now += 200
        song("New Song")
        assertEquals("New Song", title())
        now += 3_000
        assertEquals("REau", title())
        session.updateNowPlaying("New Song", "Album", "Artist", playing = false)
        assertEquals("a pause of the same song says nothing", "REau", title())
    }

    @Test
    fun `a song that changes with no tap never takes the title`() {
        song("Old Song")
        now += 20_000
        song("Next By Itself")
        assertEquals("REau", title())
    }

    @Test
    fun `while guiding the street goes for the name and comes back to the street`() {
        guide("Rua das Flores")
        song("Old Song")
        tapNext()
        song("Blue Monday")
        assertEquals("Blue Monday", title())
        now += 3_000
        assertEquals("Rua das Flores", title())
    }

    @Test
    fun `the name is cut at the title's length`() {
        song("Old Song")
        tapNext()
        song("A Really Quite Long Song Title")
        assertEquals("A Really Quite Long", title())
    }

    @Test
    fun `a name with accents reaches the card as the Latin-1 letters`() {
        song("Old Song")
        tapNext()
        song("Coração")
        assertEquals("Coração", title())
    }

    @Test
    fun `a name the dash cannot draw leaves the action's name`() {
        song("Old Song")
        tapNext()
        song("東京")
        assertEquals("Next song", title())
    }

    @Test
    fun `with the echo switched off the title stays`() {
        session.actionEcho = false
        song("Old Song")
        tapNext()
        song("Blue Monday")
        assertEquals("REau", title())
    }

    @Test
    fun `with a call on the card the song's name is not asked for, and not shown`() {
        song("Old Song")
        session.showCaller("Ana")
        session.songChangeAsked()
        song("Blue Monday")
        assertFalse(session.songEcho.waiting())
        assertEquals("REau", title())
    }

    @Test
    fun `a call that comes while the name is awaited keeps the title`() {
        song("Old Song")
        tapNext()
        session.showCaller("Ana")
        song("Blue Monday")
        assertEquals("Next song", title())
    }

    @Test
    fun `a tap after another takes the place, and the name belongs to the last`() {
        song("Old Song")
        tapNext()
        now += 500
        tapNext()
        song("Third")
        assertEquals("Third", title())
        assertFalse(session.songEcho.waiting())
    }

    @Test
    fun `the end of the session forgets the wait`() {
        song("Old Song")
        tapNext()
        session.songEcho.cancel()
        song("Blue Monday")
        assertTrue(title() == "Next song")
    }

    // ── TimedShow's own length ──

    @Test
    fun `a name shown for longer takes its own time, and the next show is back to the usual one`() {
        val show = TimedShow<String>(1_500L, nowMs = { now })
        show.show("long", forMs = 3_000L)
        now += 2_999
        assertEquals("long", show.current())
        now += 1
        assertEquals(null, show.current())
        show.show("usual")
        now += 1_499
        assertEquals("usual", show.current())
        now += 1
        assertEquals(null, show.current())
    }
}
