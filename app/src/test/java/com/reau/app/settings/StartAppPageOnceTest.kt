package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.ui.AlertRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ligar and the app's page in Android (24/11/2026): when Android refuses with no window, Ligar opens the app's page once, not at every Ligar of
 * a rider who refused on purpose (the location is optional); the projection starts whatever the answers. The mark is erased when Ligar has nothing to ask.
 */
class StartAppPageOnceTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val notifications = "android.permission.POST_NOTIFICATIONS"
    private val limit = AlertRoute.WINDOW_MIN_MS

    @Test
    fun `a quick refusal opens the page the first time`() {
        assertTrue(StartAsks.openAppPageAfter(mapOf(notifications to true, fine to false), 20, openedBefore = false))
    }

    @Test
    fun `a quick refusal opens nothing once Ligar has opened the page before`() {
        assertFalse(StartAsks.openAppPageAfter(mapOf(notifications to true, fine to false), 20, openedBefore = true))
    }

    @Test
    fun `a refusal after a window the rider answered opens nothing`() {
        assertFalse(StartAsks.openAppPageAfter(mapOf(fine to false), limit, openedBefore = false))
        assertFalse(StartAsks.openAppPageAfter(mapOf(fine to false), 3_000, openedBefore = false))
    }

    @Test
    fun `everything granted opens nothing, whether or not it opened before`() {
        assertFalse(StartAsks.openAppPageAfter(mapOf(notifications to true, fine to true), 0, openedBefore = false))
        assertFalse(StartAsks.openAppPageAfter(mapOf(notifications to true, fine to true), 0, openedBefore = true))
    }

    @Test
    fun `a window closed with no answer opens nothing`() {
        assertFalse(StartAsks.openAppPageAfter(emptyMap(), 0, openedBefore = false))
    }

    @Test
    fun `a clean install has not opened the page, and reading it writes nothing`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        assertFalse(store.startAppPageOpened())
        store.load()
        assertFalse(store.startAppPageOpened())
        assertTrue("reading writes nothing: ${p.map.keys}", p.map.isEmpty())
    }

    @Test
    fun `once marked it stays marked, also after the settings are saved`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.startAppPageOpenedNow()
        assertTrue(store.startAppPageOpened())
        store.save(REauSettings())
        assertTrue(SettingsStore(p).startAppPageOpened())
    }

    @Test
    fun `the mark has its own key`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(p).startAppPageOpenedNow()
        assertEquals(setOf("start_app_page_opened_20261124"), p.map.keys)
    }

    @Test
    fun `forgetting erases the mark and nothing else, and writes nothing when it is not there`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.notificationsAskedNow()
        store.startAppPageForget()
        assertEquals(setOf("notifications_asked_20261124"), p.map.keys)
        store.startAppPageOpenedNow()
        store.startAppPageForget()
        assertFalse(store.startAppPageOpened())
        assertEquals(setOf("notifications_asked_20261124"), p.map.keys)
    }

    @Test
    fun `Ligar starts the projection first, then asks the mark and the decision, and the mark is erased when nothing is asked`() {
        val main = File("src/main/java/com/reau/app/MainActivity.kt").readText()
        val callback = main.substringAfter("private val askBeforeStart = ").substringBefore("// The Wi-Fi screen's")
        val starts = callback.indexOf("if (startingCard) ProjectionService.startCard(this) else ProjectionService.start(this)")
        val answered = callback.indexOf("startAnswered(answers)")
        assertTrue("the projection starts in the callback", starts >= 0)
        assertTrue("the page's decision comes after the projection starts", answered > starts)
        assertTrue(main.contains("askedStartAtMs = com.reau.app.util.MonotonicClock.nowMs()\n                askBeforeStart.launch(asks.toTypedArray())"))
        assertTrue(main.contains("StartAsks.openAppPageAfter(answers, elapsed, store.startAppPageOpened())) return\n        store.startAppPageOpenedNow()"))
        assertTrue(main.contains("        PermissionRows.openAppSettings(this)\n    }\n\n    // The Wi-Fi screen's"))
        assertTrue(main.contains("if (asks.isEmpty()) SettingsStore.open(this).startAppPageForget()"))
    }
}
