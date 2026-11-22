package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch of the call notice on the dash's video ("callCard", on from the factory): kept, taken while running, named with letters only, logged with the settings in
 * use, in the settings screen and in the three languages.
 */
class CallCardSettingsTest {
    @Test
    fun `it is on from the factory, with nothing stored and after the settings are cleaned`() {
        assertTrue(REauSettings().callCard)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().callCard)
        assertTrue(REauSettings().sanitized().callCard)
    }

    @Test
    fun `the rider's choice is kept both ways`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(callCard = false))
        assertFalse(SettingsStore(prefs).load().callCard)
        SettingsStore(prefs).save(REauSettings(callCard = true))
        assertTrue(SettingsStore(prefs).load().callCard)
        assertEquals(listOf("callCard: true -> false"), REauSettings().changesTo(REauSettings(callCard = false)))
    }

    @Test
    fun `it does not move with the volume switch`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(callCard = false, volumeCard = true))
        val loaded = SettingsStore(prefs).load()
        assertFalse(loaded.callCard)
        assertTrue(loaded.volumeCard)
    }

    @Test
    fun `a change is taken by the running projection with the dash's extras, and by nothing else`() {
        val before = REauSettings()
        assertEquals(setOf(LiveTuning.Step.DASH_EXTRAS), LiveTuning.steps(before, before.copy(callCard = false)))
    }

    @Test
    fun `the settings line of the ride log says it, with a name of letters only`() {
        assertTrue(" callCard=true " in SettingsInUse.line(REauSettings()))
        assertTrue(" callCard=false " in SettingsInUse.line(REauSettings(callCard = false)))
        assertTrue("callCard".all { it.isLetter() })
        assertTrue(File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText().contains("private const val KEY_CALL_CARD = \"callCard\""))
    }

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the switch is in the dash group of the settings screen right after the volume notice, applied at once, with its texts in the three languages`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, "R.string.call_card_title, R.string.call_card_sub, R.string.call_card, s.callCard, \"dash_call_card\", now = true" in screen)
        assertTrue("change(s.copy(callCard = it))" in screen)
        assertTrue("right after the volume notice", screen.indexOf("\"dash_volume_card\"") in 0 until screen.indexOf("\"dash_call_card\""))
        assertTrue("and before the driving side", screen.indexOf("\"dash_call_card\"") < screen.indexOf("DrivingSideChoice(s, change)"))
        assertEquals("Call notice on the dash", string("values", "call_card_title"))
        assertEquals("Aviso de chamada no painel", string("values-pt", "call_card_title"))
        assertEquals("Aviso de llamada en el tablero", string("values-es", "call_card_title"))
        assertEquals(
            "Mostra no alto do vídeo quem está ligando, no lugar da tela de chamada do painel, que cobre quase tudo.",
            string("values-pt", "call_card"),
        )
        assertEquals("Shows at the top of the dash’s video who is calling, in place of the dash’s call screen, which covers almost everything.", string("values", "call_card"))
        assertEquals("Muestra arriba del video quién está llamando, en lugar de la pantalla de llamada del tablero, que cubre casi todo.", string("values-es", "call_card"))
    }

    @Test
    fun `the small line's words are in the three languages with the time in the two that carry it`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in listOf("call_card_answer", "call_card_decline", "call_card_end", "call_card_calling")) assertTrue("$folder $name", string(folder, name).isNotBlank())
            for (name in listOf("call_card_on_call", "call_card_ended")) assertTrue("$folder $name", "%1\$s" in string(folder, name))
        }
    }
}
