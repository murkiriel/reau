package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The MEDIA and PHONE keys with a picture the net does not know (ride log of 22/11/2026: the Media key, then 42 s of unsure looks at p=0.73 with the
 * screen still the map's): the key only opens an app, so after [PictureGate.UNSURE_APPS_MS] of unsure looks the screen is taken as the apps'.
 */
class AaScreenAppUnsureTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }

    @After
    fun tearDown() = AaScreens.watch.reset()

    /** The map shown, as in the log, with the first moments of the session already passed. */
    private fun onTheMap(on: AaScreenWatch = watch) {
        on.look(PictureLook.Kind.UNSURE, 1_000)
        on.look(PictureLook.Kind.UNSURE, 4_000)
        on.look(PictureLook.Kind.MAP, 5_000)
        on.look(PictureLook.Kind.MAP, 5_500)
        assertEquals(AaScreen.NAV, on.screen.value)
    }

    private fun unsure(on: AaScreenWatch, vararg at: Long) = at.forEach { assertNull("unsure at $it", on.look(PictureLook.Kind.UNSURE, it)) }

    @Test
    fun `the time is three seconds`() = assertEquals(3_000L, PictureGate.UNSURE_APPS_MS)

    @Test
    fun `unsure looks after the Media key keep the map until the time and then take the apps`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        said.clear()
        unsure(watch, 10_400, 11_400, 12_400, 12_999)
        assertEquals("still waiting before the time", AaScreen.NAV, watch.screen.value)
        assertEquals(emptyList<String>(), said)
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.UNSURE, 13_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(listOf("aa-screen: apps (the picture stayed unsure 3000 ms after the Media key)"), said)
        assertEquals("the app is not known, and not the map's none", AaApp.UNKNOWN, watch.app.value)
        assertFalse("a guess by time is not the select's wait for the picture", watch.appsPending.value)
        said.clear()
        unsure(watch, 14_000, 15_000, 30_000)
        assertEquals("said once", emptyList<String>(), said)
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `the Phone key is the same and says its name`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Phone")
        said.clear()
        unsure(watch, 11_000, 12_000)
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.UNSURE, 13_000))
        assertEquals(listOf("aa-screen: apps (the picture stayed unsure 3000 ms after the Phone key)"), said)
    }

    @Test
    fun `the process's watcher takes the apps from the Media key`() {
        val process = AaScreens.watch
        process.reset()
        onTheMap(process)
        AaScreens.keySent(ServiceDiscoveryResponse.KEY_MEDIA, nowMs = 10_000)
        process.look(PictureLook.Kind.UNSURE, 11_000)
        assertEquals(AaScreen.APPS, process.look(PictureLook.Kind.UNSURE, 13_100))
    }

    @Test
    fun `the sequence of the log, an unsure stable look every second, ends in the apps and the picture then confirms`() {
        onTheMap()
        watch.appSent(nowMs = 58_000, what = "Media")
        var taken = 0L
        for (t in 59_000L..99_000L step 1_000L) {
            if (watch.look(PictureLook.Kind.UNSURE, t) == AaScreen.APPS) taken = t
        }
        assertEquals("taken at the first look after the time, not 42 s later", 61_000L, taken)
        for (t in listOf(100_000L, 100_300L, 100_600L)) watch.look(PictureLook.Kind.APPS, t)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals("the picture's three looks confirm what was taken", "aa-screen: apps confirmed by the picture (an app)", said.last())
    }

    @Test
    fun `a sure look of a map in between ends the wait`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        unsure(watch, 10_900, 11_900)
        watch.look(PictureLook.Kind.MAP, 12_000)
        unsure(watch, 13_500, 14_500, 20_000)
        assertEquals("the picture says a map", AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `a sure look of the menu in between ends the wait`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        unsure(watch, 10_900)
        watch.look(PictureLook.Kind.MENU, 11_500)
        unsure(watch, 13_500, 20_000)
        assertEquals(AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `the old map still leaving inside the settle does not end the wait`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        watch.look(PictureLook.Kind.MAP, 10_300)
        unsure(watch, 11_000, 12_000)
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.UNSURE, 13_000))
    }

    @Test
    fun `three sure looks of an app still make the apps in about a second`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        watch.look(PictureLook.Kind.APPS, 10_900)
        assertNull(watch.look(PictureLook.Kind.APPS, 11_150))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 11_400))
        assertEquals("by the picture, not by time", "aa-screen: apps (by the picture: an app, from nav)", said.last())
        said.clear()
        unsure(watch, 14_000, 20_000)
        assertEquals("nothing is waiting any more", emptyList<String>(), said)
    }

    @Test
    fun `a sure look of an app between unsure ones does not give the time back, and the time still runs from the key`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        unsure(watch, 10_900)
        watch.look(PictureLook.Kind.APPS, 11_900)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.UNSURE, 13_000))
    }

    @Test
    fun `a sure look of an app after the time does not take the apps by time`() {
        onTheMap()
        watch.appSent(nowMs = 10_000, what = "Media")
        assertNull(watch.look(PictureLook.Kind.APPS, 13_500))
        assertEquals("one look of an app is not three", AaScreen.NAV, watch.screen.value)
        assertEquals(emptyList<String>().size, said.count { it.contains("stayed unsure") })
    }

    @Test
    fun `unsure looks with no key do not take the apps`() {
        onTheMap()
        unsure(watch, 10_000, 20_000, 60_000)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertFalse(said.any { it.contains("stayed unsure") })
    }

    @Test
    fun `the Home, Navigation and select keys, and a new session, forget the wait`() {
        val ends = listOf<(AaScreenWatch) -> Unit>(
            { it.homeSent(10_500) },
            { it.navSent(10_500) },
            { it.reset() },
        )
        for ((n, end) in ends.withIndex()) {
            val w = AaScreenWatch { said += it }
            onTheMap(w)
            w.appSent(nowMs = 10_000, what = "Media")
            end(w)
            val screen = w.screen.value
            unsure(w, 14_000, 20_000)
            assertEquals("end $n", screen, w.screen.value)
        }
    }

    @Test
    fun `a select in the menu forgets the wait, and the apps it took are the ones of the select`() {
        val w = AaScreenWatch { said += it }
        w.look(PictureLook.Kind.UNSURE, 1_000)
        w.look(PictureLook.Kind.UNSURE, 4_000)
        for (t in listOf(5_000L, 5_300L, 5_600L)) w.look(PictureLook.Kind.MENU, t)
        w.appSent(nowMs = 10_000, what = "Media")
        w.selectSent(10_500)
        assertEquals(AaScreen.APPS, w.screen.value)
        assertEquals(true, w.appsPending.value)
        said.clear()
        unsure(w, 14_000, 20_000)
        assertEquals("the select's wait is as before, no time rule", true, w.appsPending.value)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `with the apps already shown the key assumes nothing`() {
        onTheMap()
        for (t in listOf(6_000L, 6_300L, 6_600L)) watch.look(PictureLook.Kind.APPS, t)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.appSent(nowMs = 10_000, what = "Media")
        said.clear()
        unsure(watch, 14_000, 20_000)
        assertEquals(emptyList<String>(), said)
    }
}
