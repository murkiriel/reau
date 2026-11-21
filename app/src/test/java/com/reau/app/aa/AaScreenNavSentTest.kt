package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NAVIGATION key sent to Android Auto and the screen watch ([AaScreenWatch.navSent], [AaScreens.keySent]): the key opens a map, so the
 * screen waits for the picture to say a map, as the Home key waits for the menu. What is still leaving is the old picture, not news.
 */
class AaScreenNavSentTest {
    private val said = mutableListOf<String>()
    private val watch = AaScreenWatch { said += it }

    @After
    fun tearDown() = AaScreens.watch.reset()

    private fun settled() {
        watch.look(PictureLook.Kind.UNSURE, 1_000)
        assertNull(watch.look(PictureLook.Kind.UNSURE, 4_000))
    }

    private fun looks(kind: PictureLook.Kind, vararg at: Long) = at.forEach { watch.look(kind, it) }

    @Test
    fun `from the menu, the key takes no screen at once, and the picture says the map when it comes`() {
        settled()
        watch.homeSent(nowMs = 5_000)
        said.clear()
        watch.navSent(nowMs = 10_000)
        assertEquals("the screen waits for the picture", AaScreen.MENU, watch.screen.value)
        assertEquals(listOf("aa-screen: waiting for a map (the Navigation key)"), said)
        assertNull(watch.look(PictureLook.Kind.MAP, 10_100))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_350))
    }

    @Test
    fun `after the key, the menu still leaving the screen is no news, and the menu seen after the settle is`() {
        settled()
        watch.homeSent(nowMs = 5_000)
        said.clear()
        watch.navSent(nowMs = 10_000)
        said.clear()
        looks(PictureLook.Kind.MENU, 10_100, 10_350, 10_600)
        assertEquals("the old menu is not counted nor said", emptyList<String>(), said)
        assertEquals(AaScreen.MENU, watch.screen.value)
        // The key opened nothing: after the settle the menu counts again.
        looks(PictureLook.Kind.MENU, 10_900, 11_150, 11_400)
        assertTrue(said.toString(), said.any { "confirmed by the picture" in it })
    }

    @Test
    fun `from an app, the app still leaving is no news, and a map after the key is the navigation`() {
        settled()
        looks(PictureLook.Kind.APPS, 5_000, 5_500, 6_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.navSent(nowMs = 10_000)
        said.clear()
        looks(PictureLook.Kind.APPS, 10_100, 10_350, 10_600)
        assertEquals(emptyList<String>(), said)
        watch.look(PictureLook.Kind.MAP, 10_700)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_950))
    }

    @Test
    fun `with the navigation already shown the key changes nothing and says nothing`() {
        settled()
        looks(PictureLook.Kind.MAP, 5_000, 5_500)
        assertEquals(AaScreen.NAV, watch.screen.value)
        said.clear()
        watch.navSent(nowMs = 10_000)
        assertEquals(emptyList<String>(), said)
        looks(PictureLook.Kind.APPS, 10_100, 10_300)
        assertEquals("an app after it is still told at once, no settle was armed", AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 10_500))
    }

    @Test
    fun `the process's watcher is told by the key sent, and only by the navigation key`() {
        val process = AaScreens.watch
        process.reset()
        process.look(PictureLook.Kind.UNSURE, 1_000)
        process.look(PictureLook.Kind.UNSURE, 4_000)
        for (t in listOf(5_000L, 5_500L, 6_000L)) process.look(PictureLook.Kind.APPS, t)
        assertEquals(AaScreen.APPS, process.screen.value)
        AaScreens.keySent(ServiceDiscoveryResponse.KEY_NAVIGATION, nowMs = 10_000)
        // Three looks of the menu would make it at once; inside the settle after the key they are the old picture's.
        for (t in listOf(10_100L, 10_300L, 10_500L)) assertNull("the old picture at $t", process.look(PictureLook.Kind.MENU, t))
        assertEquals(AaScreen.APPS, process.screen.value)
        assertNull(process.look(PictureLook.Kind.MAP, 10_850))
        assertEquals(AaScreen.NAV, process.look(PictureLook.Kind.MAP, 11_100))
    }

    @Test
    fun `another key does not arm the wait for a map`() {
        val process = AaScreens.watch
        process.reset()
        process.look(PictureLook.Kind.UNSURE, 1_000)
        process.look(PictureLook.Kind.UNSURE, 4_000)
        for (t in listOf(5_000L, 5_500L, 6_000L)) process.look(PictureLook.Kind.APPS, t)
        AaScreens.keySent(ServiceDiscoveryResponse.KEY_BACK, nowMs = 10_000)
        process.look(PictureLook.Kind.MENU, 10_100)
        process.look(PictureLook.Kind.MENU, 10_300)
        assertEquals("nothing was waiting: three looks of the menu make it", AaScreen.MENU, process.look(PictureLook.Kind.MENU, 10_500))
    }
}
