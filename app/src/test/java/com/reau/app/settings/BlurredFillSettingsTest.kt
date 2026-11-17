package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch "Fundo desfocado nas bordas": off for a new install, kept when saved, and said in the line of the settings in use. */
class BlurredFillSettingsTest {
    @Test
    fun `it is off for a new install, read from an empty store and sanitized`() {
        assertFalse(REauSettings().blurredFill)
        assertFalse(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().blurredFill)
        assertFalse(REauSettings().sanitized(debugBuild = true).blurredFill)
        assertFalse(REauSettings().sanitized(debugBuild = false).blurredFill)
    }

    @Test
    fun `it is kept when saved on, and when saved off again`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(blurredFill = true))
        assertTrue(SettingsStore(prefs).load().blurredFill)
        assertTrue("and it survives the sanitizing", SettingsStore(prefs).load().sanitized().blurredFill)
        SettingsStore(prefs).save(REauSettings(blurredFill = false))
        assertFalse(SettingsStore(prefs).load().blurredFill)
    }

    @Test
    fun `the line of the settings in use says it`() {
        assertTrue(" blurredFill=false " in SettingsInUse.line(REauSettings()))
        assertTrue(" blurredFill=true " in SettingsInUse.line(REauSettings(blurredFill = true)))
        assertEquals(true, "blurredFill" in SettingsInUse.LOGGED)
    }
}
