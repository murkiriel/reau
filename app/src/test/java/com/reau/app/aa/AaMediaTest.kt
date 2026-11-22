package com.reau.app.aa

import com.reau.app.aa.proto.MediaPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Android Auto's playback status says is playing and which app ([MediaNowWatch], fed by [AapCards]), and the judge that puts it
 * together with the picture's app ([spotifyInFront]). Nothing here is wired to a behaviour yet: it is read and written in the ride log.
 */
class AaMediaTest {
    private val said = mutableListOf<String>()
    private val watch = MediaNowWatch { said += it }

    // ── the state ──

    @Test
    fun `nothing plays and no app is named until Android Auto says so`() {
        assertEquals(MediaNow(), watch.now.value)
        assertFalse(watch.now.value.playing)
        assertNull(watch.now.value.source)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a status that says playing and names the app is said once, and a repeat says nothing`() {
        watch.playback("Spotify", true)
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
        assertEquals(listOf("aa-media: playing, source Spotify"), said)
        repeat(3) { watch.playback("Spotify", true) }
        assertEquals("Android Auto sends the status about once a second", 1, said.size)
    }

    @Test
    fun `pausing and playing again are said, and the app stays`() {
        watch.playback("Spotify", true)
        watch.playback(null, false)
        assertEquals(MediaNow(playing = false, source = "Spotify"), watch.now.value)
        watch.playback(null, true)
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
        assertEquals(listOf("aa-media: playing, source Spotify", "aa-media: not playing, source Spotify", "aa-media: playing, source Spotify"), said)
    }

    @Test
    fun `a status that names an app and says no state keeps the state, and one with a state and no app keeps the app`() {
        watch.playback(null, true)
        assertEquals(MediaNow(playing = true, source = null), watch.now.value)
        assertEquals(listOf("aa-media: playing, source unknown"), said)
        watch.playback("Spotify", null)
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
        watch.playback(null, null)
        assertEquals("a status with neither changes nothing", 2, said.size)
    }

    @Test
    fun `another app taking over is said`() {
        watch.playback("Spotify", true)
        watch.playback("YouTube Music", true)
        assertEquals(MediaNow(playing = true, source = "YouTube Music"), watch.now.value)
        assertEquals("aa-media: playing, source YouTube Music", said.last())
    }

    @Test
    fun `the end of a session clears it, said once`() {
        watch.playback("Spotify", true)
        watch.reset()
        assertEquals(MediaNow(), watch.now.value)
        assertEquals("aa-media: cleared (the session ended)", said.last())
        watch.reset()
        assertEquals("nothing to clear the second time", 2, said.size)
    }

    // ── from the status messages of Android Auto, through AapCards ──

    private fun message(type: Int, payload: ByteArray): AapMessage {
        val bytes = byteArrayOf((type shr 8).toByte(), type.toByte()) + payload
        return AapMessage(Channel.ID_MPB, 0x0B, type, 2, bytes.size, bytes)
    }

    private fun status(state: MediaPlayback.MediaPlaybackStatus.State? = null, source: String? = null, seconds: Int? = null): AapMessage {
        val builder = MediaPlayback.MediaPlaybackStatus.newBuilder()
        if (state != null) builder.setState(state)
        if (source != null) builder.setMediaSource(source)
        if (seconds != null) builder.setPlaybackSeconds(seconds)
        return message(AapCards.MEDIA_PLAYBACK_STATUS, builder.buildPartial().toByteArray())
    }

    private val cards = AapCards(null, null, null, watch::playback)

    @Test
    fun `the playback status of Android Auto reaches the watch with the app and the state`() {
        cards.take(status(MediaPlayback.MediaPlaybackStatus.State.PLAYING, "Spotify"))
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
        cards.take(status(MediaPlayback.MediaPlaybackStatus.State.PAUSED, "Spotify"))
        assertEquals(MediaNow(playing = false, source = "Spotify"), watch.now.value)
        cards.take(status(MediaPlayback.MediaPlaybackStatus.State.STOPPED, "Spotify"))
        assertFalse("stopped is not playing", watch.now.value.playing)
    }

    @Test
    fun `a status with a blank app keeps the app, and one with neither state nor app is not passed on`() {
        cards.take(status(MediaPlayback.MediaPlaybackStatus.State.PLAYING, "Spotify"))
        cards.take(status(source = "  "))
        cards.take(status(seconds = 12))
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
        assertEquals(1, said.size)
    }

    @Test
    fun `the status is read once by the cards for the watch, the card and the app together`() {
        val songs = mutableListOf<AaNowPlaying?>()
        val apps = mutableListOf<String>()
        val calls = mutableListOf<Pair<String?, Boolean?>>()
        val both = AapCards({ songs += it }, null, { apps += it }) { source, playing -> calls += source to playing }
        both.take(message(AapCards.MEDIA_PLAYBACK_METADATA, MediaPlayback.MediaMetaData.newBuilder().setSong("Tempo Perdido").buildPartial().toByteArray()))
        both.take(status(MediaPlayback.MediaPlaybackStatus.State.PAUSED, "Spotify"))
        both.take(status(seconds = 5))
        assertEquals("the app, as before", listOf("Spotify"), apps)
        assertEquals("the card, as before", listOf(true, false), songs.map { it?.playing })
        assertEquals("one call for one status, none for a status that says neither", listOf<Pair<String?, Boolean?>>("Spotify" to false), calls)
    }

    @Test
    fun `the song never reaches the watch nor its lines`() {
        cards.take(message(AapCards.MEDIA_PLAYBACK_METADATA, MediaPlayback.MediaMetaData.newBuilder().setSong("Tempo Perdido").setArtist("Legião Urbana").setAlbum("Dois").buildPartial().toByteArray()))
        cards.take(status(MediaPlayback.MediaPlaybackStatus.State.PLAYING, "Spotify"))
        assertTrue(said.joinToString("\n"), said.none { "Tempo" in it || "Legi" in it || "Dois" in it })
        assertEquals(MediaNow(playing = true, source = "Spotify"), watch.now.value)
    }

    @Test
    fun `a metadata message alone changes nothing in the watch`() {
        cards.take(message(AapCards.MEDIA_PLAYBACK_METADATA, MediaPlayback.MediaMetaData.newBuilder().setSong("x").buildPartial().toByteArray()))
        assertEquals(MediaNow(), watch.now.value)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `the process's watch is fed by the same hook`() {
        AaMedia.watch.reset()
        val real = AapCards(null, null, null, AaMedia.watch::playback)
        real.take(status(MediaPlayback.MediaPlaybackStatus.State.PLAYING, "Spotify"))
        assertEquals(MediaNow(playing = true, source = "Spotify"), AaMedia.watch.now.value)
        AaMedia.watch.reset()
        assertEquals(MediaNow(), AaMedia.watch.now.value)
    }

    // ── the app that says Spotify ──

    @Test
    fun `the name of the app tells Spotify whatever the case, and nothing else does`() {
        assertTrue(MediaNow(true, "Spotify").fromSpotify)
        assertTrue(MediaNow(true, "spotify").fromSpotify)
        assertTrue(MediaNow(false, "Spotify Premium").fromSpotify)
        assertFalse(MediaNow(true, "YouTube Music").fromSpotify)
        assertFalse(MediaNow(true, null).fromSpotify)
        assertFalse(MediaNow(true, "").fromSpotify)
    }

    // ── the judge ──

    private class Row(val screen: AaScreen, val app: AaApp, val media: MediaNow, val expected: Boolean, val why: String)

    private val spotifyPlays = MediaNow(playing = true, source = "Spotify")
    private val spotifyPaused = MediaNow(playing = false, source = "Spotify")
    private val otherPlays = MediaNow(playing = true, source = "YouTube Music")
    private val nobody = MediaNow()

    @Test
    fun `Spotify in front by the picture, or by the music when the picture is not sure of the app`() {
        val rows = listOf(
            Row(AaScreen.APPS, AaApp.SPOTIFY, nobody, true, "the picture alone"),
            Row(AaScreen.APPS, AaApp.SPOTIFY, otherPlays, true, "the picture beats the music"),
            Row(AaScreen.APPS, AaApp.SPOTIFY, spotifyPaused, true, "paused or not"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, spotifyPlays, true, "the net is not sure, Spotify plays, an app is shown"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, spotifyPaused, false, "paused is no evidence"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, otherPlays, false, "another app plays"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, MediaNow(true, null), false, "playing, app not named"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, nobody, false, "nothing plays"),
            Row(AaScreen.APPS, AaApp.OTHER, spotifyPlays, false, "the net names another app: the net is right"),
            Row(AaScreen.NAV, AaApp.NONE, spotifyPlays, false, "the map, with Spotify playing in the background"),
            Row(AaScreen.MENU, AaApp.NONE, spotifyPlays, false, "the menu, with Spotify playing"),
            Row(AaScreen.NAV, AaApp.UNKNOWN, spotifyPlays, false, "the app is not known but the screen is the map"),
            Row(AaScreen.MENU, AaApp.UNKNOWN, spotifyPlays, false, "the app is not known but the screen is the menu"),
            Row(AaScreen.UNKNOWN, AaApp.UNKNOWN, spotifyPlays, false, "no screen yet"),
        )
        for (row in rows) assertEquals(row.why, row.expected, spotifyInFront(row.screen, row.app, row.media))
    }

    @Test
    fun `while the apps' screen waits for the picture after a select only the picture saying Spotify is Spotify`() {
        val rows = listOf(
            Row(AaScreen.APPS, AaApp.SPOTIFY, spotifyPlays, true, "the picture says Spotify"),
            Row(AaScreen.APPS, AaApp.SPOTIFY, nobody, true, "the picture alone"),
            Row(AaScreen.APPS, AaApp.UNKNOWN, spotifyPlays, false, "what opened is not known, and the music in the background says nothing of it"),
            Row(AaScreen.APPS, AaApp.OTHER, spotifyPlays, false, "another app"),
            Row(AaScreen.NAV, AaApp.NONE, spotifyPlays, false, "the map"),
        )
        for (row in rows) assertEquals(row.why, row.expected, spotifyInFront(row.screen, row.app, row.media, appsPending = true))
    }
}
