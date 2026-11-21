package com.reau.app.aa

import com.reau.app.dash.EchoLabel
import com.reau.app.video.ScreenNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick's "switch map and music" ([SwapMapMusic]): the decision from what the net sees (the map goes to the music; Spotify, the menu, another app and a
 * screen not sure all go to the map), how the screen watch's state is told in the net's words, the key a tap sends, the line it leaves in the ride log and the
 * echo it names. Nothing here needs a phone: what is in front and the log are given.
 */
class SwapMapMusicTest {
    private val navigationKey = AaCommand.Key(ServiceDiscoveryResponse.KEY_NAVIGATION)
    private val mediaKey = AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA)

    private class Front(var screen: AaScreen = AaScreen.UNKNOWN, var app: AaApp = AaApp.UNKNOWN) {
        val lines = mutableListOf<String>()
        val swap = SwapMapMusic({ screen }, { app }, { lines += it })
    }

    // ── the decision ──

    @Test
    fun `the map goes to the music`() {
        assertEquals(SwapMapMusic.Target.MUSIC, SwapMapMusic.targetFor(ScreenNet.Screen.NAV))
    }

    @Test
    fun `Spotify, the menu, another app and a screen not sure all go to the map`() {
        for (seen in listOf(ScreenNet.Screen.SPOTIFY, ScreenNet.Screen.MENU, ScreenNet.Screen.OTHER, ScreenNet.Screen.UNSURE)) {
            assertEquals("$seen", SwapMapMusic.Target.MAP, SwapMapMusic.targetFor(seen))
        }
    }

    @Test
    fun `every screen the net can name has an answer, and only the map goes to the music`() {
        val toMusic = ScreenNet.Screen.entries.filter { SwapMapMusic.targetFor(it) == SwapMapMusic.Target.MUSIC }
        assertEquals(listOf(ScreenNet.Screen.NAV), toMusic)
    }

    @Test
    fun `the music is Android Auto's media button and the map its navigation button`() {
        assertEquals(ServiceDiscoveryResponse.KEY_MEDIA, SwapMapMusic.Target.MUSIC.keycode)
        assertEquals(ServiceDiscoveryResponse.KEY_NAVIGATION, SwapMapMusic.Target.MAP.keycode)
        assertEquals(65537, SwapMapMusic.Target.MUSIC.keycode)
        assertEquals(65538, SwapMapMusic.Target.MAP.keycode)
    }

    // ── the watch's state in the net's words ──

    @Test
    fun `the screen watch's state is told in the net's words`() {
        assertEquals(ScreenNet.Screen.NAV, SwapMapMusic.seen(AaScreen.NAV, AaApp.NONE))
        assertEquals(ScreenNet.Screen.MENU, SwapMapMusic.seen(AaScreen.MENU, AaApp.NONE))
        assertEquals(ScreenNet.Screen.SPOTIFY, SwapMapMusic.seen(AaScreen.APPS, AaApp.SPOTIFY))
        assertEquals(ScreenNet.Screen.OTHER, SwapMapMusic.seen(AaScreen.APPS, AaApp.OTHER))
        assertEquals("an app whose name is not settled yet is not the map", ScreenNet.Screen.OTHER, SwapMapMusic.seen(AaScreen.APPS, AaApp.UNKNOWN))
        assertEquals(ScreenNet.Screen.UNSURE, SwapMapMusic.seen(AaScreen.UNKNOWN, AaApp.UNKNOWN))
    }

    @Test
    fun `only the settled map screen reads as the map, whatever the app says`() {
        for (app in AaApp.entries) {
            for (screen in AaScreen.entries) {
                val toMusic = SwapMapMusic.targetFor(SwapMapMusic.seen(screen, app)) == SwapMapMusic.Target.MUSIC
                assertEquals("$screen/$app", screen == AaScreen.NAV, toMusic)
            }
        }
    }

    // ── the tap ──

    @Test
    fun `a tap on the map sends the media button and says so in the log`() {
        val front = Front(AaScreen.NAV, AaApp.NONE)
        assertEquals(mediaKey, front.swap.resolve())
        assertEquals(listOf("joystick swap: screen NAV -> music"), front.lines)
    }

    @Test
    fun `a tap on Spotify sends the navigation button and says so in the log`() {
        val front = Front(AaScreen.APPS, AaApp.SPOTIFY)
        assertEquals(navigationKey, front.swap.resolve())
        assertEquals(listOf("joystick swap: screen SPOTIFY -> map"), front.lines)
    }

    @Test
    fun `a tap on the menu, another app or a screen not known sends the navigation button`() {
        val cases = listOf(
            Triple(AaScreen.MENU, AaApp.NONE, "MENU"), Triple(AaScreen.APPS, AaApp.OTHER, "OTHER"), Triple(AaScreen.UNKNOWN, AaApp.UNKNOWN, "UNSURE"),
        )
        for ((screen, app, word) in cases) {
            val front = Front(screen, app)
            assertEquals(word, navigationKey, front.swap.resolve())
            assertEquals(word, listOf("joystick swap: screen $word -> map"), front.lines)
        }
    }

    @Test
    fun `the screen is read at the moment of each tap, not before`() {
        val front = Front(AaScreen.NAV, AaApp.NONE)
        assertEquals(mediaKey, front.swap.resolve())
        front.screen = AaScreen.APPS
        front.app = AaApp.SPOTIFY
        assertEquals(navigationKey, front.swap.resolve())
        front.screen = AaScreen.NAV
        front.app = AaApp.NONE
        assertEquals(mediaKey, front.swap.resolve())
        assertEquals(3, front.lines.size)
    }

    @Test
    fun `a tap writes one line, and the line has no name of an app or of a song`() {
        val front = Front(AaScreen.APPS, AaApp.SPOTIFY)
        front.swap.resolve()
        assertEquals(1, front.lines.size)
        // The screen the net named is a class of the net, not the app's name as Android Auto says it; the line is that and where the tap went, nothing more.
        assertTrue(front.lines.single(), Regex("""joystick swap: screen [A-Z]+ -> (music|map)""").matches(front.lines.single()))
    }

    @Test
    fun `the log line is the same words for every screen`() {
        assertEquals("joystick swap: screen NAV -> music", SwapMapMusic.logLine(ScreenNet.Screen.NAV, SwapMapMusic.Target.MUSIC))
        assertEquals("joystick swap: screen SPOTIFY -> map", SwapMapMusic.logLine(ScreenNet.Screen.SPOTIFY, SwapMapMusic.Target.MAP))
    }

    // ── the echo ──

    @Test
    fun `before any tap no target is known`() {
        assertNull(Front().swap.lastTarget)
    }

    @Test
    fun `the echo names where the tap went`() {
        val front = Front(AaScreen.NAV, AaApp.NONE)
        front.swap.resolve()
        assertEquals(SwapMapMusic.Target.MUSIC, front.swap.lastTarget)
        assertEquals(EchoLabel.SWAP_TO_MUSIC, SwapMapMusic.echoFor(EchoLabel.SWAP_MAP_MUSIC, front.swap.lastTarget))
        front.screen = AaScreen.APPS
        front.app = AaApp.OTHER
        front.swap.resolve()
        assertEquals(SwapMapMusic.Target.MAP, front.swap.lastTarget)
        assertEquals(EchoLabel.SWAP_TO_MAP, SwapMapMusic.echoFor(EchoLabel.SWAP_MAP_MUSIC, front.swap.lastTarget))
    }

    @Test
    fun `only the swap's label is changed by the target`() {
        for (label in EchoLabel.entries - EchoLabel.SWAP_MAP_MUSIC) {
            assertEquals("$label", label, SwapMapMusic.echoFor(label, SwapMapMusic.Target.MUSIC))
            assertEquals("$label", label, SwapMapMusic.echoFor(label, SwapMapMusic.Target.MAP))
        }
        assertEquals("with no target the legend's name stays", EchoLabel.SWAP_MAP_MUSIC, SwapMapMusic.echoFor(EchoLabel.SWAP_MAP_MUSIC, null))
        assertFalse(EchoLabel.SWAP_TO_MUSIC.changesSong || EchoLabel.SWAP_TO_MAP.changesSong)
    }
}
