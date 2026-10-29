package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy: a switch "Usar a localização", on by default (28/10/2026) but reading nothing until the location permission is given, and
 * reading as soon as it is. Off, REau reads no position (the night mode goes by the time zone). The Wi-Fi list, to find the dash's whole name, goes by the location permission alone (on Android 14 the
 * nearby-devices permission gave an empty list).
 */
class LocationUseTest {
    @Test
    fun `the switch is on until switched off, and kept off when saved off`() {
        assertEquals(true, REauSettings().useLocation)
        assertEquals(true, SettingsStore(com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs()).load().useLocation)
        val prefs = com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(useLocation = false))
        assertEquals(false, SettingsStore(prefs).load().useLocation)
    }

    @Test
    fun `a switch left on reads nothing until a location permission is given, then reads by itself`() {
        // The place is learned at each return to the screen (MainActivity.onResume), asking this again with what is granted then.
        assertFalse("on, no permission", LocationUse.mayReadPlace(useLocation = true, fineGranted = false, coarseGranted = false))
        assertTrue("on, the approximate one given later", LocationUse.mayReadPlace(useLocation = true, fineGranted = false, coarseGranted = true))
        assertTrue("on, the precise one given later", LocationUse.mayReadPlace(useLocation = true, fineGranted = true, coarseGranted = false))
        assertFalse("off, permission given", LocationUse.mayReadPlace(useLocation = false, fineGranted = true, coarseGranted = true))
    }

    @Test
    fun `the Wi-Fi list is read with the location permission, whatever the switch`() {
        assertTrue(LocationUse.mayScan(locationGranted = true))
        assertFalse(LocationUse.mayScan(locationGranted = false))
    }
}
