package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music mode after a select in the menu or a tap on the side bar's shortcuts (22/11/2026, the ride: Spotify playing, Waze picked in the menu, and the
 * gold strip showed the music card for 0.3 to 1.1 s before the map): the apps' screen is only a guess until the picture confirms it
 * ([AaScreenWatch.appsPending]), so the mode comes on only when the picture says Spotify, or when it confirmed an app and the net is not sure which one while
 * Spotify plays ([spotifyInFront]). The watch, the mode and the media status here are the real ones, fed as the projection feeds them.
 */
class MusicWaitsForPictureTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }
    private val mode = MusicMode { said += it }
    private val spotifyPlays = MediaNow(playing = true, source = "Spotify")

    /** What the projection does at every change: the mode is told the screen, the app, the media status and whether the apps' screen waits for the picture. */
    private fun feed(nowMs: Long, media: MediaNow = spotifyPlays) = mode.update(watch.screen.value, watch.app.value, media, nowMs, watch.appsPending.value)

    private fun look(kind: PictureLook.Kind, at: Long, seen: AaApp = AaApp.UNKNOWN) {
        watch.look(kind, at, seen)
        feed(at)
    }

    /** The menu shown with Spotify playing in the background, a select at 10 s. */
    private fun selectInMenu() {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        watch.look(PictureLook.Kind.UNSURE, 4_000)
        watch.homeSent()
        feed(9_000)
        assertFalse(mode.active(9_000))
        watch.selectSent(nowMs = 10_000)
        feed(10_000)
    }

    private fun musicOn() = said.filter { it.startsWith("music mode") }

    @Test
    fun `a select in the menu with Spotify playing does not turn the music mode on`() {
        selectInMenu()
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertTrue(watch.appsPending.value)
        assertFalse(mode.active(10_000))
        assertFalse(mode.playing(10_000))
        feed(10_300)
        feed(10_600)
        assertFalse(mode.active(10_600))
        assertEquals("the wait is said once", listOf("music mode waits for the picture: Spotify plays, the app that opened is not known yet"), musicOn())
    }

    @Test
    fun `the picture says a map after the select, so the mode never came on`() {
        selectInMenu()
        look(PictureLook.Kind.MAP, 10_300)
        look(PictureLook.Kind.MAP, 10_600)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertFalse("the screen is no more waiting", watch.appsPending.value)
        assertFalse(mode.active(10_600))
        assertFalse(mode.active(30_000))
        assertTrue(said.toString(), said.none { "music mode on" in it || "music mode off" in it })
    }

    @Test
    fun `the picture says Spotify after the select, and the mode comes on when it confirms`() {
        selectInMenu()
        // The menu still leaving, then the apps' looks from the settle on (10.8 s).
        look(PictureLook.Kind.MENU, 10_300)
        look(PictureLook.Kind.APPS, 10_900, AaApp.SPOTIFY)
        look(PictureLook.Kind.APPS, 11_150, AaApp.SPOTIFY)
        assertFalse("two looks are not a confirmation", mode.active(11_150))
        assertTrue(watch.appsPending.value)
        look(PictureLook.Kind.APPS, 11_400, AaApp.SPOTIFY)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
        assertFalse(watch.appsPending.value)
        assertTrue(mode.active(11_400))
        assertTrue(mode.playing(11_400))
        assertTrue(said.toString(), "music mode on: Spotify playing" in said)
    }

    @Test
    fun `the picture confirms an app it cannot name while Spotify plays, and the old guess works after the confirmation`() {
        selectInMenu()
        look(PictureLook.Kind.APPS, 10_900)
        look(PictureLook.Kind.APPS, 11_150)
        assertFalse(mode.active(11_150))
        look(PictureLook.Kind.APPS, 11_400)
        assertTrue(said.toString(), said.any { "apps confirmed by the picture (an app)" in it })
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertFalse(watch.appsPending.value)
        assertTrue("the net is not sure of the app, the screen is an app's and Spotify plays", mode.active(11_400))
    }

    @Test
    fun `the picture names another app, and the mode stays off through the confirmation`() {
        selectInMenu()
        look(PictureLook.Kind.APPS, 10_900, AaApp.OTHER)
        look(PictureLook.Kind.APPS, 11_150, AaApp.OTHER)
        look(PictureLook.Kind.APPS, 11_400, AaApp.OTHER)
        assertEquals(AaApp.OTHER, watch.app.value)
        assertFalse(mode.active(11_400))
        assertTrue(said.toString(), said.none { "music mode on" in it })
    }

    @Test
    fun `a tap on the side bar's shortcuts with Spotify in front ends the mode until the picture says what opened`() {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        watch.look(PictureLook.Kind.UNSURE, 4_000)
        for (t in listOf(5_000L, 5_500L, 6_000L)) look(PictureLook.Kind.APPS, t, AaApp.SPOTIFY)
        assertTrue(mode.active(6_000))
        watch.look(PictureLook.Kind.MAP, 9_500)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        feed(10_000)
        assertTrue(watch.appsPending.value)
        assertFalse("the shortcut may take Android Auto back to the map", mode.active(10_000))
        look(PictureLook.Kind.MAP, 10_100)
        look(PictureLook.Kind.MAP, 10_350)
        assertFalse(mode.active(10_350))
        assertEquals(AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `the Home key and the end of the session end the wait`() {
        selectInMenu()
        assertTrue(watch.appsPending.value)
        watch.homeSent(nowMs = 10_500)
        assertFalse(watch.appsPending.value)
        watch.selectSent(nowMs = 11_000)
        assertTrue(watch.appsPending.value)
        watch.reset()
        assertFalse(watch.appsPending.value)
    }

    @Test
    fun `a select that changes nothing does not start a wait`() {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        watch.look(PictureLook.Kind.UNSURE, 4_000)
        for (t in listOf(5_000L, 5_500L)) watch.look(PictureLook.Kind.MAP, t)
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertFalse(watch.appsPending.value)
    }
}
