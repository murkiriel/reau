package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * What a rider's switch is kept as on the phone: the key, a name of letters. A key that was renamed would leave the choice the rider made
 * under the old name and read the factory value instead, so the name is pinned by what the store writes and reads, not by the text of its source.
 */
internal object StoredKey {
    /**
     * Saving [make]'s settings puts the switch under [key], and a file that holds [key] is read as that switch ([read]); the value tried is
     * the one that is not the factory's, so a switch that is never written or never read fails too.
     */
    fun assertKeptUnder(key: String, make: (Boolean) -> REauSettings, read: (REauSettings) -> Boolean) {
        assertTrue("$key is a name of letters only", key.all { it.isLetter() })
        val factory = read(REauSettings())
        val chosen = !factory
        val written = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(written).save(make(chosen))
        assertTrue("saving writes $key", written.contains(key))
        assertEquals("saving writes the choice under $key", chosen, written.getBoolean(key, factory))
        val stored = KeystoreBackedPrefsTest.FakePrefs()
        stored.edit().putBoolean(key, chosen).apply()
        assertEquals("a file that holds $key is read from it", chosen, read(SettingsStore(stored).load()))
    }
}
