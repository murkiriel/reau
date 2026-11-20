package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dash's music card with Spotify in front ([DashSession.musicModePlaying], what [com.reau.app.aa.MusicMode.playing] says), on the wire to [FakeDashPeer]:
 * playing, the whole card goes (song, "playing" flag) even when the joystick page has no music control; paused or on another screen, the page decides alone,
 * as it always did; and the card follows the change from playing to paused and back. What the dash draws of it is not proved here.
 */
class DashSessionMusicModeCardTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
    }

    private fun streaming(): Pair<DashSession, FakeDashPeer> {
        val fake = FakeDashPeer().also { fakeDash = it }
        val s = DashSession(scope, socketFactory = { DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest) }).also { session = it }
        s.connect("DashFake")
        runBlocking {
            val deadline = System.currentTimeMillis() + 8_000
            while (s.state.value != DashState.READY && s.state.value != DashState.ERROR && System.currentTimeMillis() < deadline) delay(50)
        }
        assertEquals(DashState.READY, s.state.value)
        s.startStreaming()
        fake.ackIdrFrame()
        // The dash talks as a real one does, or the receive watchdog ends the rounds under test.
        scope.launch { while (isActive) { fake.ackPFrame(); delay(500) } }
        return s to fake
    }

    private fun pause(ms: Long) = runBlocking { delay(ms) }

    private fun FakeDashPeer.cards() = countOf(0x05, 0x0D)
    private fun FakeDashPeer.last(sub: Int) = received.last { it.type == 0x05 && it.sub == sub }.value.toList()

    @Test
    fun `playing with Spotify in front sends the whole card at once, with no music control on the page`() {
        val (s, fake) = streaming()
        s.musicCardAllowed = false
        s.updateNowPlaying("Song", "Album", "Band", playing = true)
        pause(1_300)
        assertEquals("the page has no music control and Spotify is not in front yet: no card", 0, fake.cards())
        s.musicModePlaying = true
        pause(600)
        assertTrue("the card came at once, not at the next round", fake.cards() >= 1)
        assertArrayEquals("album, title, artist", "Album\u0000Song\u0000Band".toByteArray(Charsets.UTF_8), fake.received.last { it.type == 0x05 && it.sub == 0x0D }.value)
        assertEquals("music on", listOf(0x55.toByte()), fake.last(0x17))
        assertEquals("playing, which the dash draws as the pause button", listOf(0x55.toByte()), fake.last(0x19))
    }

    @Test
    fun `paused, or Spotify gone, with no music control on the page there is no card, as before`() {
        val (s, fake) = streaming()
        s.musicCardAllowed = false
        s.updateNowPlaying("Song", "Album", "Band", playing = false)
        pause(1_300)
        assertEquals("Spotify in front but paused: the page's say alone", 0, fake.cards())
        s.musicModePlaying = true
        pause(500)
        val shown = fake.cards()
        s.musicModePlaying = false
        pause(1_300)
        assertEquals("the card was cleared when the music stopped playing", listOf(0xAA.toByte()), fake.last(0x17))
        val after = fake.cards()
        pause(1_300)
        assertEquals("and is not repeated", after, fake.cards())
        assertTrue("at most the one card in between", after - shown <= 1)
        s.musicModePlaying = true
        pause(600)
        assertTrue("playing again brings it back", fake.cards() > after)
        assertEquals(listOf(0x55.toByte()), fake.last(0x17))
    }

    @Test
    fun `with a music control on the page the card is the page's, playing or not, and the mode changes nothing`() {
        val (s, fake) = streaming()
        s.musicCardAllowed = true
        s.updateNowPlaying("Song", "Album", "Band", playing = false)
        pause(600)
        assertTrue("paused: the card goes, as it always did", fake.cards() >= 1)
        assertEquals("paused flag", listOf(0xAA.toByte()), fake.last(0x19))
        s.musicModePlaying = true
        s.musicModePlaying = false
        pause(1_300)
        assertEquals("the card stays and is not cleared by the mode's going", listOf(0x55.toByte()), fake.last(0x17))
    }
}
