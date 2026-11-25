package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mark that REau already asked for the notification permission (24/11/2026): the alert card of a clean install waits for it. */
class NotificationsAskedTest {
    @Test
    fun `a clean install has not asked, and asking about it writes nothing`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        assertFalse(store.notificationsAsked())
        store.load()
        assertFalse(store.notificationsAsked())
        assertTrue("reading writes nothing: ${p.map.keys}", p.map.isEmpty())
    }

    @Test
    fun `once marked it stays marked, also after the settings are saved`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.notificationsAskedNow()
        assertTrue(store.notificationsAsked())
        store.save(REauSettings())
        assertTrue(SettingsStore(p).notificationsAsked())
    }

    @Test
    fun `the mark has its own key`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(p).notificationsAskedNow()
        assertEquals(setOf("notifications_asked_20261124"), p.map.keys)
    }

    @Test
    fun `the notification permission is asked where the mark is written`() {
        val main = java.io.File("src/main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("if (StartAsks.NOTIFICATIONS in answers) SettingsStore.open(this).notificationsAskedNow()"))
        // The start's question and the permissions page's both go through it.
        assertEquals(2, Regex("""(?m)^\s+notificationsAsked\(answers\)$""").findAll(main).count())
        assertTrue(main.contains("askNotifications.launch(StartAsks.NOTIFICATIONS)"))
        assertTrue(main.contains("com.reau.app.ui.AlertSettings.answered(this, granted, askedNotificationsAtMs)"))
    }
}
