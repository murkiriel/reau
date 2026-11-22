package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch of the volume notice on the dash's video ("volumeCard", on from the factory): kept, taken while running, named with letters only, logged with
 * the settings in use, in the settings screen and in the three languages.
 */
class VolumeCardSettingsTest {
    @Test
    fun `it is on from the factory, with nothing stored and after the settings are cleaned`() {
        assertTrue(REauSettings().volumeCard)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().volumeCard)
        assertTrue(REauSettings().sanitized().volumeCard)
    }

    @Test
    fun `the rider's choice is kept both ways`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(volumeCard = false))
        assertFalse(SettingsStore(prefs).load().volumeCard)
        SettingsStore(prefs).save(REauSettings(volumeCard = true))
        assertTrue(SettingsStore(prefs).load().volumeCard)
        assertEquals(listOf("volumeCard: true -> false"), REauSettings().changesTo(REauSettings(volumeCard = false)))
    }

    @Test
    fun `a change is taken by the running projection with the dash's extras, and by nothing else`() {
        val before = REauSettings()
        assertEquals(setOf(LiveTuning.Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(volumeCard = false)))
    }

    @Test
    fun `the settings line of the ride log says it, with a name of letters only`() {
        assertTrue(" volumeCard=true " in SettingsInUse.line(REauSettings()))
        assertTrue(" volumeCard=false " in SettingsInUse.line(REauSettings(volumeCard = false)))
        assertTrue("volumeCard".all { it.isLetter() })
        assertTrue(File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText().contains("private const val KEY_VOLUME_CARD = \"volumeCard\""))
    }

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the switch is in the dash group of the settings screen, applied at once, with its texts in the three languages`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, "R.string.volume_card_title, R.string.volume_card_sub, R.string.volume_card, s.volumeCard, \"dash_volume_card\", now = true" in screen)
        assertTrue("change(s.copy(volumeCard = it))" in screen)
        assertEquals("Volume notice on the dash", string("values", "volume_card_title"))
        assertEquals("Aviso de volume no painel", string("values-pt", "volume_card_title"))
        assertEquals("Aviso de volumen en el tablero", string("values-es", "volume_card_title"))
        assertEquals(
            "Mostra no alto do vídeo do painel o volume da música ou da chamada sempre que ele muda, no lugar do aviso no título.",
            string("values-pt", "volume_card"),
        )
    }

    @Test
    fun `the card's words are music, call and muted with the level out of the most, in the three languages`() {
        val expected = mapOf(
            "values" to listOf("Music %1\$d/%2\$d", "Call %1\$d/%2\$d", "Muted %1\$d/%2\$d"),
            "values-pt" to listOf("Música %1\$d/%2\$d", "Chamada %1\$d/%2\$d", "Mudo %1\$d/%2\$d"),
            "values-es" to listOf("Música %1\$d/%2\$d", "Llamada %1\$d/%2\$d", "Silencio %1\$d/%2\$d"),
        )
        for ((folder, words) in expected) {
            assertEquals(folder, words, listOf("volume_card_music", "volume_card_call", "volume_card_muted").map { string(folder, it) })
        }
    }
}
