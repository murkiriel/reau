package com.reau.app.aa

import com.reau.app.video.PictureLook
import com.reau.app.video.ScreenNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which app is in front ([AaScreenWatch.app]), said by the same looks as the screen: Spotify and another app are both the apps' screen
 * ([AaScreen.APPS], nothing changes for the crop), and the app settles after the same three looks in a row that make the apps' screen.
 */
class AaAppTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }

    private val apps = PictureLook.Kind.APPS
    private val spotify = AaApp.SPOTIFY
    private val other = AaApp.OTHER

    /** The watch past the first moments of a session: the first look at 1 s, so the picture counts from 4 s on. */
    private fun settled(): AaScreenWatch {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        assertNull(watch.look(PictureLook.Kind.UNSURE, 4_000))
        return watch
    }

    /** Looks of an app that the net called [seen], 500 ms apart from [from]; the last answer. */
    private fun appLooks(seen: AaApp, count: Int, from: Long): AaScreen? =
        (0 until count).map { watch.look(apps, from + it * 500L, seen) }.last()

    // ── the net's class goes to the app ──

    @Test
    fun `the net's five answers become the app of one look`() {
        assertEquals(AaApp.SPOTIFY, AaApp.of(ScreenNet.Screen.SPOTIFY))
        assertEquals(AaApp.OTHER, AaApp.of(ScreenNet.Screen.OTHER))
        assertEquals(AaApp.NONE, AaApp.of(ScreenNet.Screen.NAV))
        assertEquals(AaApp.NONE, AaApp.of(ScreenNet.Screen.MENU))
        assertEquals(AaApp.UNKNOWN, AaApp.of(ScreenNet.Screen.UNSURE))
        assertEquals(listOf(AaApp.SPOTIFY, AaApp.OTHER), AaApp.entries.filter { it.named })
    }

    // ── the screen does not change ──

    @Test
    fun `Spotify and another app both make the apps' screen after the same three looks`() {
        for (seen in listOf(spotify, other)) {
            watch.reset()
            settled()
            assertNull(watch.look(apps, 5_000, seen))
            assertNull(watch.look(apps, 5_500, seen))
            assertEquals("$seen", AaScreen.APPS, watch.look(apps, 6_000, seen))
            assertEquals("$seen", AaScreen.APPS, watch.screen.value)
        }
    }

    @Test
    fun `an app the net does not name still makes the apps' screen, and leaves the app unknown`() {
        settled()
        // the way every caller before the app existed looks: no app given
        val answers = (0 until 3).map { watch.look(apps, 5_000L + it * 500L) }
        assertEquals(listOf(null, null, AaScreen.APPS), answers)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertTrue("nothing is said of an app that was not named", said.none { it.startsWith("aa-screen: app ") })
    }

    @Test
    fun `Spotify and the other app in turn do not break the apps' screen`() {
        settled()
        val answers = listOf(spotify, other, spotify, other, spotify, other).mapIndexed { i, seen -> watch.look(apps, 5_000L + i * 500L, seen) }
        assertEquals("the screen is taken once, at the third look, and kept", listOf(null, null, AaScreen.APPS, null, null, null), answers)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals("the app never settles with the net changing its mind at every look", AaApp.UNKNOWN, watch.app.value)
    }

    // ── the app ──

    @Test
    fun `the app is unknown at the start, and nothing is read in the first 3 seconds`() {
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        for (t in listOf(1_000L, 1_500L, 2_000L, 2_500L)) watch.look(apps, t, spotify)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
    }

    @Test
    fun `Spotify is the app after three looks of Spotify in a row, said once`() {
        settled()
        watch.look(apps, 5_000, spotify)
        watch.look(apps, 5_500, spotify)
        assertEquals("two looks are not enough", AaApp.UNKNOWN, watch.app.value)
        watch.look(apps, 6_000, spotify)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
        assertEquals(1, said.count { it.startsWith("aa-screen: app spotify") })
        assertTrue(said.joinToString("\n"), "from unknown" in said.single { it.startsWith("aa-screen: app ") })
        for (t in listOf(6_500L, 7_000L, 7_500L)) watch.look(apps, t, spotify)
        assertEquals("a repeat says nothing", 1, said.count { it.startsWith("aa-screen: app ") })
    }

    @Test
    fun `the other app is the app after three looks of another app in a row`() {
        settled()
        appLooks(other, 3, 5_000)
        assertEquals(AaApp.OTHER, watch.app.value)
        assertEquals(1, said.count { it.startsWith("aa-screen: app other") })
    }

    @Test
    fun `the apps' screen taken before the app is settled has an unknown app, then the app when the looks agree`() {
        settled()
        watch.look(apps, 5_000, spotify)
        watch.look(apps, 5_500, spotify)
        watch.look(apps, 6_000, other)
        assertEquals("three looks of apps, not of one app", AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        watch.look(apps, 6_500, spotify)
        watch.look(apps, 7_000, spotify)
        assertEquals("two looks of Spotify after the other app", AaApp.UNKNOWN, watch.app.value)
        watch.look(apps, 7_500, spotify)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
    }

    @Test
    fun `from Spotify to another app takes three looks of the other app, and says it once`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.look(apps, 7_000, other)
        watch.look(apps, 7_500, other)
        assertEquals("still Spotify", AaApp.SPOTIFY, watch.app.value)
        watch.look(apps, 8_000, other)
        assertEquals(AaApp.OTHER, watch.app.value)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.last(), said.last().startsWith("aa-screen: app other") && "from spotify" in said.last())
    }

    @Test
    fun `a look that is not sure keeps the app and starts the count over`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.look(PictureLook.Kind.UNSURE, 7_000)
        watch.look(apps, 7_500, other)
        watch.look(apps, 8_000, other)
        assertEquals("the unsure look kept Spotify, and two looks of the other app are not enough", AaApp.SPOTIFY, watch.app.value)
        watch.look(PictureLook.Kind.UNSURE, 8_500)
        watch.look(apps, 9_000, other)
        watch.look(apps, 9_500, other)
        assertEquals("the unsure look started the count over", AaApp.SPOTIFY, watch.app.value)
        watch.look(apps, 10_000, other)
        assertEquals(AaApp.OTHER, watch.app.value)
    }

    @Test
    fun `a look of an app that is called Spotify but is not of an app is no look of Spotify`() {
        settled()
        for (t in listOf(5_000L, 5_500L, 6_000L, 6_500L)) watch.look(PictureLook.Kind.MAP, t, spotify)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertEquals(AaApp.NONE, watch.app.value)
        assertTrue("nothing said", said.none { it.startsWith("aa-screen: app ") })
    }

    // ── the map and the menu have no app ──

    @Test
    fun `the map and the menu have no app, and Spotify leaving is said`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.look(PictureLook.Kind.MAP, 7_000)
        assertEquals("one look of a map does not take the screen away", AaApp.SPOTIFY, watch.app.value)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 7_500))
        assertEquals(AaApp.NONE, watch.app.value)
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("aa-screen: app none") && "from spotify" in it })
        for (t in listOf(8_000L, 8_500L, 9_000L)) watch.look(PictureLook.Kind.MENU, t)
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertEquals(AaApp.NONE, watch.app.value)
        assertEquals("said once", 1, said.count { it.startsWith("aa-screen: app none") })
    }

    @Test
    fun `from the map the app is known at the third look, when the screen becomes the apps'`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaApp.NONE, watch.app.value)
        assertEquals(AaScreen.APPS, appLooks(other, 3, 6_000))
        assertEquals(AaApp.OTHER, watch.app.value)
    }

    private fun looks(kind: PictureLook.Kind, vararg at: Long) = at.forEach { watch.look(kind, it) }

    @Test
    fun `the apps' screen taken from the map before the app settles has an unknown app, not none`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaApp.NONE, watch.app.value)
        watch.look(apps, 6_000, spotify)
        watch.look(apps, 6_500, other)
        assertEquals("two looks of apps: the map is still the screen", AaApp.NONE, watch.app.value)
        assertEquals(AaScreen.APPS, watch.look(apps, 7_000, spotify))
        assertEquals("the screen is the apps' and no app is settled: there is no 'none' on an apps' screen", AaApp.UNKNOWN, watch.app.value)
    }

    @Test
    fun `the Home key takes the app away at once`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.homeSent(nowMs = 7_000)
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertEquals(AaApp.NONE, watch.app.value)
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("aa-screen: app none") && "the Home key" in it })
    }

    @Test
    fun `a select in the menu makes the app unknown until the picture says it, and the old picture is not the app`() {
        settled()
        looks(PictureLook.Kind.MENU, 5_000, 5_500, 6_000)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        // the first 800 ms still show what was in front: they do not count, even for Spotify
        for (t in listOf(10_100L, 10_300L, 10_500L)) watch.look(apps, t, spotify)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        appLooks(other, 3, 10_900)
        assertEquals(AaApp.OTHER, watch.app.value)
    }

    @Test
    fun `a tap on the side bar with an app in front forgets the app, and the next app is the picture's`() {
        settled()
        appLooks(spotify, 3, 5_000)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.NAVIGATION)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("aa-screen: app unknown") && "from spotify" in it })
        // the three looks of Spotify before the tap do not carry over: the app is what the picture says after the tap, three looks of it
        watch.look(apps, 10_900, spotify)
        watch.look(apps, 11_400, spotify)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        watch.look(apps, 11_900, spotify)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
    }

    @Test
    fun `the media shortcut that opens nothing new leaves the app as it was`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.look(apps, 6_600, spotify)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertEquals("the last look was of an app, not a map: the media shortcut opens an app and the watch waits for nothing", AaApp.SPOTIFY, watch.app.value)
    }

    @Test
    fun `a session that ends forgets the app`() {
        settled()
        appLooks(spotify, 3, 5_000)
        watch.reset()
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        settled()
        assertEquals("the count of the old session does not carry over", AaApp.UNKNOWN, watch.app.value)
        watch.look(apps, 5_000, spotify)
        watch.look(apps, 5_500, spotify)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
    }

    // ── from the picture to the watch, as the compositor and the live service do it ──

    private val w = PictureLook.SAMPLE_W
    private val h = PictureLook.SAMPLE_H

    /** A flat gray picture (the net reads 46 as another app). */
    private fun gray(v: Int) = ByteArray(w * h * 4) { if (it % 4 == 3) (-1).toByte() else v.toByte() }

    /** An olive band over olive with a magenta square: the net reads it as Spotify (the same picture as in PictureLookTest). */
    private fun spotifyLike() = ByteArray(w * h * 4).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val rgb = when {
                y < 19 -> intArrayOf(45, 81, 17)
                x in 18..44 && y >= 22 -> intArrayOf(251, 7, 234)
                else -> intArrayOf(127, 104, 18)
            }
            px[o] = rgb[0].toByte(); px[o + 1] = rgb[1].toByte(); px[o + 2] = rgb[2].toByte(); px[o + 3] = -1
        }
    }

    /** What the compositor's look does: the picture to the kind, the net's own class to the app, both to the watch. */
    private fun lookAt(picture: ByteArray, nowMs: Long): AaScreen? {
        val into = ScreenNet.Result()
        val kind = PictureLook.of(picture, w, h, into)
        return watch.look(kind, nowMs, AaApp.of(into.screen))
    }

    @Test
    fun `a picture the net reads as Spotify and one it reads as another app both make the apps' screen, each with its app`() {
        for ((picture, app) in listOf(spotifyLike() to spotify, gray(46) to other)) {
            watch.reset()
            settled()
            assertNull(lookAt(picture, 5_000))
            assertNull(lookAt(picture, 5_500))
            assertEquals("$app", AaScreen.APPS, lookAt(picture, 6_000))
            assertEquals(app, watch.app.value)
        }
    }

    @Test
    fun `a map picture gives the map and no app`() {
        settled()
        val white = gray(255)
        assertNull(lookAt(white, 5_000))
        assertEquals(AaScreen.NAV, lookAt(white, 5_500))
        assertEquals(AaApp.NONE, watch.app.value)
    }

    // ── the gate of the looks, alone ──

    @Test
    fun `the app gate settles on the third look of the same app, and any other look starts it over`() {
        val gate = AppGate()
        assertNull(gate.next(apps, spotify))
        assertNull(gate.next(apps, spotify))
        assertEquals(spotify, gate.next(apps, spotify))
        assertEquals("the fourth look settles on it too", spotify, gate.next(apps, spotify))
        assertNull("the other app starts its own count", gate.next(apps, other))
        assertNull(gate.next(apps, other))
        assertEquals(other, gate.next(apps, other))
        assertNull(gate.next(PictureLook.Kind.UNSURE, spotify))
        assertNull(gate.next(apps, other))
        assertNull(gate.next(apps, other))
        assertEquals("three more after the unsure look", other, gate.next(apps, other))
        assertNull(gate.next(PictureLook.Kind.MAP, other))
        assertNull("an app look with no name starts it over", gate.next(apps, AaApp.UNKNOWN))
        assertNull(gate.next(apps, other))
    }

    @Test
    fun `the app gate counts three looks, like the screen`() {
        assertEquals(PictureGate.OTHER_LOOKS, AppGate.LOOKS)
        assertEquals(3, AppGate.LOOKS)
    }
}
