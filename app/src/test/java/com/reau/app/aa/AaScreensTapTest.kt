package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tap on the phone's screen does to the process's screen watch ([AaScreens.tapSent]): where it lands on Android Auto's video decides. */
class AaScreensTapTest {
    private val launcher = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.system.applauncher.GhAppLauncherService}"
    private val spotify = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.apphost.TemplateService}"
    private val inSpotify = listOf(AaScreenRule.Display(2, spotify, true), AaScreenRule.Display(3, launcher, false))
    private val inMenu = listOf(AaScreenRule.Display(2, spotify, false), AaScreenRule.Display(3, launcher, true))

    @After
    fun tearDown() = AaScreens.watch.reset()

    private fun spotifyShown(look: PictureLook.Kind? = PictureLook.Kind.MAP): AaScreenWatch {
        AaScreens.watch.reset()
        AaScreens.watch.update(inSpotify, 0)
        assertEquals(AaScreen.APPS, AaScreens.watch.screen.value)
        look?.let { AaScreens.watch.look(it, 9_500) }
        return AaScreens.watch
    }

    @Test
    fun `a tap on a shortcut of the side bar with an app in front waits for the displays, and guesses the map when none comes`() {
        for (y in listOf(199, 277)) {
            val watch = spotifyShown()
            AaScreens.tapSent(755, y, nowMs = 10_000)
            assertNull("$y", watch.update(inSpotify, 10_300))
            assertEquals("$y", AaScreen.NAV, watch.update(inSpotify, 10_800))
        }
    }

    @Test
    fun `the media shortcut after a dark picture does not guess, the navigation one does`() {
        val media = spotifyShown(look = PictureLook.Kind.APPS)
        AaScreens.tapSent(755, 277, nowMs = 10_000)
        assertNull(media.update(inSpotify, 10_800))
        assertEquals(AaScreen.APPS, media.screen.value)
        val navigation = spotifyShown(look = PictureLook.Kind.APPS)
        AaScreens.tapSent(755, 199, nowMs = 10_000)
        assertEquals(AaScreen.NAV, navigation.update(inSpotify, 10_800))
    }

    @Test
    fun `a tap on the picture, on the microphone or on the menu's button does not wait`() {
        for ((x, y) in listOf(400 to 200, 755 to 353, 755 to 431)) {
            val watch = spotifyShown()
            AaScreens.tapSent(x, y, nowMs = 10_000)
            assertNull("$x,$y", watch.update(inSpotify, 10_800))
            assertEquals("$x,$y", AaScreen.APPS, watch.screen.value)
        }
    }

    @Test
    fun `a tap in the menu is a select wherever it lands`() {
        AaScreens.watch.reset()
        AaScreens.watch.update(inMenu, 0)
        assertEquals(AaScreen.MENU, AaScreens.watch.screen.value)
        AaScreens.tapSent(400, 200, nowMs = 10_000)
        assertEquals(AaScreen.APPS, AaScreens.watch.screen.value)
    }
}
