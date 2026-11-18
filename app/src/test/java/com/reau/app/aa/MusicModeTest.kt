package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the joystick's music mode is on ([MusicMode]): Spotify in front and playing; after a pause, 5 s more; never when the app in front is another
 * one, whatever plays. The clock is the test's. The mode's effect on the buttons is [JoystickMusicTest]'s.
 */
class MusicModeTest {
    private val said = mutableListOf<String>()
    private val mode = MusicMode { said += it }

    private val spotify = MediaNow(playing = true, source = "Spotify")
    private val spotifyPaused = MediaNow(playing = false, source = "Spotify")

    @Test
    fun `nothing is known, the mode is off`() {
        assertFalse(mode.active(0))
        assertNull(mode.expiresAtMs())
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `Spotify in front and playing turns it on`() {
        mode.update(front = true, playing = true, nowMs = 1_000)
        assertTrue(mode.active(1_000))
        assertTrue("and stays on while it plays", mode.active(900_000))
        assertEquals(listOf("music mode on: Spotify playing"), said)
    }

    @Test
    fun `Spotify in front but not playing, and never played, is off`() {
        mode.update(front = true, playing = false, nowMs = 1_000)
        assertFalse(mode.active(1_001))
        assertFalse(mode.active(60_000))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `playing with Spotify not in front is off`() {
        mode.update(front = false, playing = true, nowMs = 1_000)
        assertFalse(mode.active(1_001))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `after a pause the mode stays 4 point 9 s and ends at 5 s`() {
        mode.update(true, true, 0)
        mode.update(true, false, 10_000)
        assertTrue(mode.active(10_000))
        assertTrue("4.9 s after the pause", mode.active(14_900))
        assertEquals(15_000L, mode.expiresAtMs())
        assertFalse("5.0 s after the pause", mode.active(15_000))
        assertFalse("and it stays off", mode.active(15_001))
        assertNull(mode.expiresAtMs())
        assertEquals(listOf("music mode on: Spotify playing", "music mode off: Spotify paused 5 s ago"), said)
    }

    @Test
    fun `the pause counts from the moment the music stopped, not from the next press`() {
        mode.update(true, true, 0)
        mode.update(true, false, 10_000)
        // A repeat of the same status later does not start the count again.
        mode.update(true, false, 13_000)
        assertFalse(mode.active(15_000))
    }

    @Test
    fun `the music playing again within the 5 s keeps the mode on and the next pause counts again`() {
        mode.update(true, true, 0)
        mode.update(true, false, 10_000)
        mode.update(true, true, 14_000)
        assertTrue(mode.active(30_000))
        mode.update(true, false, 31_000)
        assertTrue(mode.active(35_999))
        assertFalse(mode.active(36_000))
        assertEquals(listOf("music mode on: Spotify playing", "music mode off: Spotify paused 5 s ago"), said)
    }

    @Test
    fun `playing again after the 5 s is a new switching on`() {
        mode.update(true, true, 0)
        mode.update(true, false, 1_000)
        mode.update(true, true, 7_000)
        assertTrue(mode.active(7_000))
        assertEquals(
            listOf("music mode on: Spotify playing", "music mode off: Spotify paused 5 s ago", "music mode on: Spotify playing"),
            said,
        )
    }

    @Test
    fun `Spotify leaving the screen ends the mode at once, playing or paused`() {
        mode.update(true, true, 0)
        mode.update(false, true, 1_000)
        assertFalse(mode.active(1_000))
        mode.update(true, true, 2_000)
        mode.update(true, false, 3_000)
        mode.update(false, false, 4_000)
        assertFalse("in the 5 s of a pause too", mode.active(4_000))
        assertEquals(
            listOf("music mode on: Spotify playing", "music mode off: Spotify left the screen", "music mode on: Spotify playing", "music mode off: Spotify left the screen"),
            said,
        )
    }

    @Test
    fun `back on Spotify's screen after a pause that left it does not bring the mode back`() {
        mode.update(true, true, 0)
        mode.update(true, false, 1_000)
        mode.update(false, false, 2_000)
        mode.update(true, false, 3_000)
        assertFalse(mode.active(3_000))
    }

    @Test
    fun `the switching off is said once, with the reason, even if nobody asks at once`() {
        mode.update(true, true, 0)
        mode.update(true, false, 1_000)
        mode.active(6_000)
        mode.active(7_000)
        mode.update(true, false, 8_000)
        assertEquals(listOf("music mode on: Spotify playing", "music mode off: Spotify paused 5 s ago"), said)
    }

    @Test
    fun `an update after the pause ran out ends it before applying the new state`() {
        mode.update(true, true, 0)
        mode.update(true, false, 1_000)
        mode.update(true, true, 9_000)
        assertEquals(listOf("music mode on: Spotify playing", "music mode off: Spotify paused 5 s ago", "music mode on: Spotify playing"), said)
    }

    @Test
    fun `the log never carries the song`() {
        mode.update(true, true, 0)
        mode.update(false, true, 1)
        assertTrue(said.all { it.startsWith("music mode ") && !it.contains("song") })
    }

    // The watches' own words: the picture's app and the media status.

    @Test
    fun `the picture says Spotify and it plays`() {
        mode.update(AaScreen.APPS, AaApp.SPOTIFY, spotify, 0)
        assertTrue(mode.active(0))
    }

    @Test
    fun `the net unsure of the app but the apps screen with Spotify playing is Spotify`() {
        mode.update(AaScreen.APPS, AaApp.UNKNOWN, spotify, 0)
        assertTrue(mode.active(0))
    }

    @Test
    fun `another app in front with Spotify playing behind is not the mode`() {
        mode.update(AaScreen.APPS, AaApp.OTHER, spotify, 0)
        assertFalse(mode.active(0))
        mode.update(AaScreen.NAV, AaApp.NONE, spotify, 1)
        assertFalse("nor the map", mode.active(1))
        mode.update(AaScreen.MENU, AaApp.NONE, spotify, 2)
        assertFalse("nor the menu", mode.active(2))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `from Spotify's screen to another app the mode ends at once`() {
        mode.update(AaScreen.APPS, AaApp.SPOTIFY, spotify, 0)
        mode.update(AaScreen.APPS, AaApp.OTHER, spotify, 500)
        assertFalse(mode.active(500))
        assertEquals("music mode off: Spotify left the screen", said.last())
    }

    @Test
    fun `Spotify's screen and a pause`() {
        mode.update(AaScreen.APPS, AaApp.SPOTIFY, spotify, 0)
        mode.update(AaScreen.APPS, AaApp.SPOTIFY, spotifyPaused, 2_000)
        assertTrue(mode.active(6_999))
        assertFalse(mode.active(7_000))
    }
}
