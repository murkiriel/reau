package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Botão central abre a música" of the joystick page's map tab ([REauSettings.mapCenterOpensMusic]): off by default, kept, taken while
 * running, logged with the settings in use, in its own place of the screen, apart from the page's reset, and with its texts in the three languages.
 */
class MapCenterOpensMusicSettingsTest {
    @Test
    fun `it is off on a new install, with nothing stored and after sanitizing`() {
        assertFalse(REauSettings().mapCenterOpensMusic)
        assertFalse(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().mapCenterOpensMusic)
        assertFalse(REauSettings().sanitized().mapCenterOpensMusic)
    }

    @Test
    fun `the rider's choice is saved and read back, under its own key`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(mapCenterOpensMusic = true))
        assertTrue(SettingsStore(prefs).load().mapCenterOpensMusic)
        assertEquals(true, prefs.getBoolean("mapCenterOpensMusic", false))
        SettingsStore(prefs).save(REauSettings(mapCenterOpensMusic = false))
        assertFalse(SettingsStore(prefs).load().mapCenterOpensMusic)
        assertTrue("the others stay as they were", SettingsStore(prefs).load().actionEcho)
    }

    @Test
    fun `the page's reset leaves it as it is`() {
        val on = REauSettings(mapCenterOpensMusic = true)
        val afterReset = on.copy(joystick = REauSettings.DEFAULT_JOYSTICK)
        assertTrue(afterReset.mapCenterOpensMusic)
        val screen = File("src/main/java/com/reau/app/ui/JoystickScreen.kt").readText()
        assertTrue(screen, "onSettings(settings.copy(joystick = REauSettings.DEFAULT_JOYSTICK))" in screen)
    }

    @Test
    fun `a change is taken by the running projection at the next tap`() {
        val before = REauSettings()
        assertEquals(setOf(LiveTuning.Step.JOYSTICK), LiveTuning.steps(before, before.copy(mapCenterOpensMusic = true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy(mapCenterOpensMusic = false)))
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "joystick.mapCenterOpensMusic = setup.settings?.mapCenterOpensMusic == true" in live)
        assertTrue(live, "joystick?.mapCenterOpensMusic = settings.mapCenterOpensMusic" in live)
        assertTrue(live, "joystick.musicInFront = {" in live)
    }

    @Test
    fun `the settings line of the ride log says it`() {
        assertTrue(SettingsInUse.line(REauSettings()), " mapCenterOpensMusic=false " in SettingsInUse.line(REauSettings()))
        assertTrue(" mapCenterOpensMusic=true " in SettingsInUse.line(REauSettings(mapCenterOpensMusic = true)))
        assertEquals(listOf("mapCenterOpensMusic: false -> true"), REauSettings().changesTo(REauSettings(mapCenterOpensMusic = true)))
    }

    @Test
    fun `the switch is on the map tab, between the control's card and the reset, with its texts in the three languages`() {
        val screen = File("src/main/java/com/reau/app/ui/JoystickScreen.kt").readText()
        assertTrue(screen, "if (mode == Mode.MAP) {\n                        SettingsGroup(" in screen)
        assertTrue(screen, "{ onSettings(settings.copy(mapCenterOpensMusic = it)) }, \"map_center_opens_music\"" in screen)
        val card = screen.indexOf("SelectedCard(slot, page[slot], locked)")
        val switch = screen.indexOf("R.string.map_center_opens_music_title")
        val reset = screen.indexOf("R.string.joystick_reset")
        assertTrue(card in 0 until switch && switch < reset)
        fun string(folder: String, name: String) =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
        assertEquals("Center button opens music", string("values", "map_center_opens_music_title"))
        assertEquals("Botão central abre a música", string("values-pt", "map_center_opens_music_title"))
        assertEquals("El botón central abre la música", string("values-es", "map_center_opens_music_title"))
        assertEquals("Ao pôr o painel no modo mídia, abre também o app de música no Android Auto.", string("values-pt", "map_center_opens_music_sub"))
        assertTrue(string("values", "map_center_opens_music_sub").isNotBlank() && string("values-es", "map_center_opens_music_sub").isNotBlank())
    }

    @Test
    fun `the key name is made of letters`() {
        val store = File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText()
        assertTrue(store, "private const val KEY_MAP_CENTER_OPENS_MUSIC = \"mapCenterOpensMusic\"" in store)
    }
}
