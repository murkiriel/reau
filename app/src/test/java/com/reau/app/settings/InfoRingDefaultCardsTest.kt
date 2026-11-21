package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.weather.RingData
import com.reau.app.weather.RingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info ring starts with every card again since 21/11/2026 (the owner's phone's settings made the defaults). From 12/11/2026 to 21/11/2026 it started
 * with three, the rain, the wind and the thunder ([RingData.DEFAULT], still in the code), and a phone that kept "every card" was moved to those three
 * once. That move is gone: every choice of the rider, "every card" included, is left as it is, and only the mark is written.
 */
class InfoRingDefaultCardsTest {
    private fun prefs(vararg saved: Pair<String, Int>, other: Boolean = true) = KeystoreBackedPrefsTest.FakePrefs().also { p ->
        p.edit().apply {
            // Something else saved before, so the store is not new.
            if (other) putInt("density", 200)
            for ((key, value) in saved) putInt(key, value)
        }.apply()
    }

    @Test
    fun `a new setting says every card since 21-11-2026, the rain, the wind and the thunder only was the default from 12-11-2026`() {
        assertEquals(RingData.RAIN.bit or RingData.WIND.bit or RingData.STORM.bit, RingData.DEFAULT)
        assertEquals(7, RingData.DEFAULT)
        assertEquals(RingData.ALL, REauSettings().infoRingData)
        assertEquals(2047, REauSettings().infoRingData)
        for (data in RingData.entries) assertTrue("$data at first", RingData.shown(REauSettings().infoRingData, data))
    }

    @Test
    fun `every page is on at first, and the three cards of 12-11-2026 (RingData DEFAULT) are still the rain, the wind and the thunder`() {
        val every = RingKind.entries.filter { RingData.shown(REauSettings().infoRingData, RingData.of(it)) }.toSet()
        assertEquals("every page, with the new default", RingKind.entries.toSet(), every)
        val on = RingKind.entries.filter { RingData.shown(RingData.DEFAULT, RingData.of(it)) }.toSet()
        assertEquals(
            setOf(RingKind.RAIN_SOON, RingKind.RAIN_NOW, RingKind.WIND_DANGER, RingKind.WIND_ALERT, RingKind.WIND_ATTENTION, RingKind.THUNDER), on,
        )
        for (kind in listOf(RingKind.UV, RingKind.AIR, RingKind.SUN, RingKind.SKY, RingKind.TEMPS, RingKind.BATTERY, RingKind.LINK, RingKind.GPS)) {
            assertFalse("$kind is off with the three cards", kind in on)
        }
    }

    @Test
    fun `the other cards are not gone, every kind can still be ticked and the default is held to the bits there are`() {
        assertEquals(2047, RingData.ALL)
        assertTrue(RingData.DEFAULT and RingData.ALL.inv() == 0)
        val every = REauSettings(infoRingData = RingData.ALL).sanitized()
        assertEquals(RingData.ALL, every.infoRingData)
        for (data in RingData.entries) assertTrue(RingData.shown(every.infoRingData, data))
    }

    @Test
    fun `a store with nothing saved reads the new default, every card`() {
        assertEquals(RingData.ALL, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().infoRingData)
    }

    @Test
    fun `a phone that kept every card keeps it, only the mark is written (21-11-2026, it was moved to the three cards once from 12-11-2026)`() {
        for (old in listOf(RingData.ALL, RingData.BEFORE_PHONE_CARDS)) {
            val p = prefs("info_ring_data" to old)
            assertEquals("kept $old (the old 255 reads as every card)", RingData.ALL, SettingsStore(p).load().infoRingData)
            assertEquals("and the choice is left as it was written", old, p.getInt("info_ring_data", -1))
            assertTrue("and only the mark is written, so the setting is never touched by this", p.getBoolean("info_ring_cards_20261112", false))
        }
    }

    @Test
    fun `a phone that has the three cards of 12-11-2026 keeps them, it is the rider's choice now`() {
        val p = prefs("info_ring_data" to RingData.DEFAULT)
        assertEquals(RingData.DEFAULT, SettingsStore(p).load().infoRingData)
        assertEquals(RingData.DEFAULT, p.getInt("info_ring_data", -1))
        assertEquals(RingData.DEFAULT, SettingsStore(p).load().infoRingData)
    }

    @Test
    fun `a narrower choice of the rider is left as it was`() {
        for (own in listOf(0, RingData.UV.bit, RingData.AIR.bit or RingData.SUN.bit, 0b1010, RingData.ALL and RingData.GPS.bit.inv())) {
            assertEquals("choice $own", own, SettingsStore(prefs("info_ring_data" to own)).load().infoRingData)
        }
    }

    @Test
    fun `a phone with settings saved but no choice for the cards gets the new default, every card`() {
        assertEquals(RingData.ALL, SettingsStore(prefs()).load().infoRingData)
    }

    @Test
    fun `no move is made, so a rider who unticks a card and ticks every card again keeps them`() {
        val p = prefs("info_ring_data" to RingData.ALL)
        val first = SettingsStore(p).load()
        assertEquals(RingData.ALL, first.infoRingData)
        // The rider unticks the GPS card, then ticks every card again; saving carries the mark, and no start takes them back.
        SettingsStore(p).save(first.copy(infoRingData = RingData.ALL and RingData.GPS.bit.inv()))
        assertEquals(RingData.ALL and RingData.GPS.bit.inv(), SettingsStore(p).load().infoRingData)
        SettingsStore(p).save(first.copy(infoRingData = RingData.ALL))
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
    }

    @Test
    fun `a phone that started with the new default and saved keeps the default, and a later narrower choice too`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.save(store.load())
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
        store.save(store.load().copy(infoRingData = RingData.DEFAULT))
        assertEquals(RingData.DEFAULT, SettingsStore(p).load().infoRingData)
    }

    @Test
    fun `the choice of the cards is saved and read as it is`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(p).save(REauSettings(infoRingData = RingData.RAIN.bit or RingData.UV.bit))
        assertEquals(RingData.RAIN.bit or RingData.UV.bit, SettingsStore(p).load().infoRingData)
    }
}
