package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The MEDIA and PHONE keys sent to Android Auto and the screen watch ([AaScreenWatch.appSent], [AaScreens.keySent]): the key opens an app, so the
 * screen waits for the picture to say it, and what is still leaving the screen (a map, the menu, the app that was in front) is the old picture, not news.
 */
class AaScreenAppSentTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }

    @After
    fun tearDown() = AaScreens.watch.reset()

    private fun settled(on: AaScreenWatch = watch) {
        on.look(PictureLook.Kind.UNSURE, 1_000)
        assertNull(on.look(PictureLook.Kind.UNSURE, 4_000))
    }

    private fun looks(on: AaScreenWatch, kind: PictureLook.Kind, vararg at: Long) = at.forEach { on.look(kind, it) }

    @Test
    fun `from the navigation the key takes no screen at once, the old map is no news and the app comes after the settle`() {
        settled()
        looks(watch, PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertEquals(AaApp.NONE, watch.app.value)
        said.clear()
        watch.appSent(nowMs = 10_000, what = "Media")
        assertEquals("the screen waits for the picture", AaScreen.NAV, watch.screen.value)
        assertEquals("a map has no app, key or not", AaApp.NONE, watch.app.value)
        assertEquals(listOf("aa-screen: waiting for an app (the Media key)"), said)
        said.clear()
        looks(watch, PictureLook.Kind.MAP, 10_100, 10_300, 10_500)
        assertEquals("the map leaving is not counted nor said", emptyList<String>(), said)
        for (t in listOf(10_100L, 10_350L, 10_600L)) assertNull("the old picture at $t", watch.look(PictureLook.Kind.APPS, t))
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.look(PictureLook.Kind.APPS, 10_900)
        assertNull(watch.look(PictureLook.Kind.APPS, 11_150))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 11_400))
    }

    @Test
    fun `with an app in front the app is not known until the picture says it, and the old one still leaving does not count`() {
        settled()
        for (t in listOf(5_000L, 5_500L, 6_000L)) watch.look(PictureLook.Kind.APPS, t, AaApp.SPOTIFY)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.SPOTIFY, watch.app.value)
        said.clear()
        watch.appSent(nowMs = 10_000, what = "Phone")
        assertEquals("the screen is still the apps'", AaScreen.APPS, watch.screen.value)
        assertEquals(AaApp.UNKNOWN, watch.app.value)
        assertEquals(listOf("aa-screen: app unknown (the Phone key, from spotify)", "aa-screen: waiting for an app (the Phone key)"), said)
        for (t in listOf(10_100L, 10_350L, 10_600L)) watch.look(PictureLook.Kind.APPS, t, AaApp.SPOTIFY)
        assertEquals("the old app leaving is no news", AaApp.UNKNOWN, watch.app.value)
        for (t in listOf(10_900L, 11_150L, 11_400L)) watch.look(PictureLook.Kind.APPS, t, AaApp.OTHER)
        assertEquals(AaApp.OTHER, watch.app.value)
    }

    @Test
    fun `from the menu the process's watcher is told by both keys, a map in the first moments is the old picture's`() {
        val process = AaScreens.watch
        for (key in listOf(ServiceDiscoveryResponse.KEY_MEDIA, ServiceDiscoveryResponse.KEY_TEL)) {
            process.reset()
            settled(process)
            looks(process, PictureLook.Kind.MENU, 5_000, 5_500, 6_000)
            assertEquals("$key", AaScreen.MENU, process.screen.value)
            AaScreens.keySent(key, nowMs = 10_000)
            process.look(PictureLook.Kind.MAP, 10_100)
            assertNull("$key: a map inside the settle", process.look(PictureLook.Kind.MAP, 10_300))
            assertEquals("$key: the screen did not move", AaScreen.MENU, process.screen.value)
            process.look(PictureLook.Kind.APPS, 10_900)
            process.look(PictureLook.Kind.APPS, 11_150)
            assertEquals("$key: the app after the settle", AaScreen.APPS, process.look(PictureLook.Kind.APPS, 11_400))
        }
    }

    @Test
    fun `another key does not arm the wait for an app`() {
        val process = AaScreens.watch
        process.reset()
        settled(process)
        looks(process, PictureLook.Kind.MENU, 5_000, 5_500, 6_000)
        AaScreens.keySent(ServiceDiscoveryResponse.KEY_BACK, nowMs = 10_000)
        process.look(PictureLook.Kind.MAP, 10_100)
        assertEquals("nothing was waiting: two looks of a map make it", AaScreen.NAV, process.look(PictureLook.Kind.MAP, 10_300))
    }
}
