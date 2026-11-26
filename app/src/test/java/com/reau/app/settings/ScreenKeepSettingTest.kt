package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.ui.ExperimentalMark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three settings of "Tela acesa com o celular bloqueado (teste)": off by default, kept, logged, and in the settings screen in the three languages. */
class ScreenKeepSettingTest {
    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `it comes off on a new install and with nothing stored, in the mode of every screen-off and with no lock screen`() {
        for (s in listOf(REauSettings(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load(), REauSettings().sanitized())) {
            assertFalse(s.screenKeep)
            assertEquals(ScreenKeepMode.ON_SCREEN_OFF, s.screenKeepMode)
            assertFalse(s.screenKeepLockUi)
        }
    }

    @Test
    fun `the rider's choices are kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(screenKeep = true, screenKeepMode = ScreenKeepMode.EVERY_30_S, screenKeepLockUi = true))
        val loaded = SettingsStore(prefs).load()
        assertTrue(loaded.screenKeep)
        assertEquals(ScreenKeepMode.EVERY_30_S, loaded.screenKeepMode)
        assertTrue(loaded.screenKeepLockUi)
        SettingsStore(prefs).save(REauSettings())
        assertFalse(SettingsStore(prefs).load().screenKeep)
    }

    @Test
    fun `a mode the store does not know goes back to the default`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.map["screen_keep_mode"] = "EVERY_5_S"
        assertEquals(ScreenKeepMode.ON_SCREEN_OFF, SettingsStore(prefs).load().screenKeepMode)
    }

    @Test
    fun `the modes say their interval and the short word of the log`() {
        assertEquals(listOf("off", "n60", "n30"), ScreenKeepMode.entries.map { it.logName })
        assertEquals(listOf(null, 60, 30), ScreenKeepMode.entries.map { it.intervalSec })
    }

    @Test
    fun `the settings line of the ride log says all three and the change is said`() {
        val on = REauSettings(screenKeep = true, screenKeepMode = ScreenKeepMode.EVERY_60_S, screenKeepLockUi = true)
        assertTrue(" screenKeep=false screenKeepMode=ON_SCREEN_OFF screenKeepLockUi=false" in SettingsInUse.line(REauSettings()))
        assertTrue("screenKeep=true screenKeepMode=EVERY_60_S screenKeepLockUi=true" in SettingsInUse.line(on))
        assertEquals(
            listOf("screenKeep: false -> true", "screenKeepMode: ON_SCREEN_OFF -> EVERY_60_S", "screenKeepLockUi: false -> true"),
            REauSettings().changesTo(on),
        )
    }

    @Test
    fun `the group is in the settings screen, with the two other rows only while the switch is on`() {
        val group = File("src/main/java/com/reau/app/ui/SettingsScreenKeep.kt").readText()
        assertTrue("{ change(s.copy(screenKeep = it)) }, \"screen_keep\"" in group)
        assertTrue("if (s.screenKeep) {" in group.substringAfter("val rows = buildList"))
        assertTrue("change(s.copy(screenKeepMode = ScreenKeepMode.entries[it]))" in group)
        assertTrue("{ change(s.copy(screenKeepLockUi = it)) }, \"screen_keep_lock_ui\"" in group)
        assertTrue("ScreenKeepGroup(settings, change)" in File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText())
    }

    @Test
    fun `the switch is called a test in Portuguese, so its row is red, and no other row of the group is`() {
        assertEquals("Tela acesa com o celular bloqueado (teste)", string("values-pt", "screen_keep_title"))
        assertTrue(ExperimentalMark.marks(string("values-pt", "screen_keep_title"), string("values-pt", "screen_keep_sub")))
        for (name in listOf("screen_keep_mode_title", "screen_keep_lock_ui_title", "screen_keep_lock_ui_sub")) {
            assertFalse(name, ExperimentalMark.marks(string("values-pt", name)))
        }
        assertEquals(
            "Na viagem, o REau acende a tela quando ela apaga, para o celular não fechá-lo. Gasta mais bateria",
            string("values-pt", "screen_keep_sub"),
        )
    }

    @Test
    fun `every text of the function is in the three languages`() {
        val names = listOf(
            "group_screen_keep", "screen_keep_title", "screen_keep_sub", "screen_keep_info", "screen_keep_mode_title", "screen_keep_mode_off",
            "screen_keep_mode_60", "screen_keep_mode_30", "screen_keep_lock_ui_title", "screen_keep_lock_ui_sub", "screen_keep_lock_projecting",
            "screen_keep_lock_swipe", "screen_keep_lock_pause",
        )
        for (folder in listOf("values", "values-pt", "values-es")) for (name in names) assertTrue("$folder $name", string(folder, name).isNotBlank())
        assertEquals("Sempre que a tela apagar", string("values-pt", "screen_keep_mode_off"))
        assertEquals("A cada 60 segundos", string("values-pt", "screen_keep_mode_60"))
        assertEquals("A cada 30 segundos", string("values-pt", "screen_keep_mode_30"))
        assertEquals("Mostrar o REau sobre o bloqueio", string("values-pt", "screen_keep_lock_ui_title"))
        assertEquals("Projetando no painel", string("values-pt", "screen_keep_lock_projecting"))
        assertEquals("Deslize para cima para desbloquear", string("values-pt", "screen_keep_lock_swipe"))
        assertEquals("Apagar a tela nesta viagem", string("values-pt", "screen_keep_lock_pause"))
    }
}
