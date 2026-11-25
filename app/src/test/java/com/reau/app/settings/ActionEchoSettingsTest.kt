package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two switches of P30 Eco, the action's name in the dash's title ("actionEcho", on by default) and the joystick's legend on its video
 * ("joystickLegend", on until 21/11/2026, off by default since then: the owner's phone's settings made the defaults): kept, taken while running, named with letters only, logged with the settings in use and in the settings screen in the three languages.
 */
class ActionEchoSettingsTest {
    @Test
    fun `the echo comes on and the legend comes off (21-11-2026) on a new install and with nothing stored`() {
        val fresh = REauSettings()
        assertTrue(fresh.actionEcho)
        assertFalse(fresh.joystickLegend)
        val loaded = SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load()
        assertTrue(loaded.actionEcho)
        assertFalse(loaded.joystickLegend)
        assertTrue(fresh.sanitized().actionEcho && !fresh.sanitized().joystickLegend)
    }

    @Test
    fun `the rider's choice is kept, each on its own`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(actionEcho = false, joystickLegend = true))
        SettingsStore(prefs).load().let {
            assertFalse(it.actionEcho)
            assertTrue("the other is kept on", it.joystickLegend)
        }
        SettingsStore(prefs).save(REauSettings(actionEcho = true, joystickLegend = false))
        SettingsStore(prefs).load().let {
            assertTrue(it.actionEcho)
            assertFalse(it.joystickLegend)
        }
        SettingsStore(prefs).save(REauSettings(actionEcho = false, joystickLegend = false))
        SettingsStore(prefs).load().let { assertFalse(it.actionEcho || it.joystickLegend) }
        SettingsStore(prefs).save(REauSettings(actionEcho = true, joystickLegend = true))
        SettingsStore(prefs).load().let { assertTrue(it.actionEcho && it.joystickLegend) }
    }

    @Test
    fun `a change is taken by the running projection at the next tap, as the joystick page is`() {
        val before = REauSettings()
        assertEquals(setOf(LiveTuning.Step.JOYSTICK), LiveTuning.steps(before, before.copy(actionEcho = false)))
        assertEquals(setOf(LiveTuning.Step.JOYSTICK), LiveTuning.steps(before, before.copy(joystickLegend = true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
    }

    @Test
    fun `the settings line of the ride log says both, with names of letters only`() {
        val line = SettingsInUse.line(REauSettings())
        assertTrue(line, " actionEcho=true joystickLegend=false " in line)
        val off = SettingsInUse.line(REauSettings(actionEcho = false, joystickLegend = true))
        assertTrue(off, " actionEcho=false joystickLegend=true " in off)
        for (name in listOf("actionEcho", "joystickLegend")) assertTrue(name, name.all { it.isLetter() })
        assertEquals(listOf("actionEcho: true -> false"), REauSettings().changesTo(REauSettings(actionEcho = false)))
    }

    @Test
    fun `the switches are in the joystick group of the settings screen, applied at once, with their texts in the three languages`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, "SettingSwitch(R.string.action_echo_title, R.string.action_echo_sub, R.string.action_echo, s.actionEcho, \"actionEcho\", now = true)" in screen)
        assertTrue(screen, "R.string.joystick_legend_title, R.string.joystick_legend_sub, R.string.joystick_legend, s.joystickLegend, \"joystickLegend\", now = true," in screen)
        assertTrue("change(s.copy(actionEcho = it))" in screen && "change(s.copy(joystickLegend = it))" in screen)
        fun string(folder: String, name: String) =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
        assertEquals("Action name on the dash", string("values", "action_echo_title"))
        assertEquals("Nome da ação no painel", string("values-pt", "action_echo_title"))
        assertEquals("Nombre de la acción en el tablero", string("values-es", "action_echo_title"))
        assertEquals("Joystick legend on the dash", string("values", "joystick_legend_title"))
        assertEquals("Legenda do joystick no painel", string("values-pt", "joystick_legend_title"))
        assertEquals("Leyenda del joystick en el tablero", string("values-es", "joystick_legend_title"))
        assertTrue("action_echo: says on by default in English", "On by default" in string("values", "action_echo"))
        assertTrue("and in Portuguese", "Ligada por padrão" in string("values-pt", "action_echo"))
        assertTrue("and in Spanish", "Activado por defecto" in string("values-es", "action_echo"))
        assertTrue("joystick_legend: says off by default (21/11/2026) in English", "Off by default" in string("values", "joystick_legend"))
        assertTrue("and in Portuguese", "Desligada por padrão" in string("values-pt", "joystick_legend"))
        assertTrue("and in Spanish", "Desactivada por defecto" in string("values-es", "joystick_legend"))
    }

    @Test
    fun `the key names of the new settings are made of letters`() {
        val store = File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText()
        assertTrue(store, "private const val KEY_ACTION_ECHO = \"actionEcho\"" in store)
        assertTrue(store, "private const val KEY_JOYSTICK_LEGEND = \"joystickLegend\"" in store)
    }
}
