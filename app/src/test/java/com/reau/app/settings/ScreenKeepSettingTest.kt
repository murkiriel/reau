package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.ui.ExperimentalMark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two settings of "Tela acesa com o celular bloqueado (teste)": the switch comes ON by default in the 0.3.9x test builds and "Mostrar o REau sobre o bloqueio" comes off;
 * both are kept, logged, and in the settings screen in the three languages. The interval modes of 0.3.94 are gone, and what an older build saved of them is never read.
 */
class ScreenKeepSettingTest {
    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the switch comes on and the lock screen choice comes off, on a new install and with nothing stored`() {
        for (s in listOf(REauSettings(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load(), REauSettings().sanitized())) {
            assertTrue(s.screenKeep)
            assertFalse(s.screenKeepLockUi)
        }
    }

    @Test
    fun `the rider's choices are kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(screenKeep = false, screenKeepLockUi = true))
        val loaded = SettingsStore(prefs).load()
        assertFalse(loaded.screenKeep)
        assertTrue(loaded.screenKeepLockUi)
        SettingsStore(prefs).save(REauSettings(screenKeep = true))
        assertTrue(SettingsStore(prefs).load().screenKeep)
        assertFalse(SettingsStore(prefs).load().screenKeepLockUi)
    }

    @Test
    fun `what an older build saved is never read, the off the 0_3_94 saved with every setting and the interval modes`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        prefs.map["screen_keep"] = false
        prefs.map["screen_keep_mode"] = "EVERY_60_S"
        val loaded = SettingsStore(prefs).load()
        assertTrue("the old off does not hold the new default off", loaded.screenKeep)
        for (saved in listOf("EVERY_30_S", "EVERY_240_S", "ON_SCREEN_OFF", "nonsense")) {
            prefs.map["screen_keep_mode"] = saved
            assertTrue(saved, SettingsStore(prefs).load().screenKeep)
        }
        assertFalse("no field of the interval is left", REauSettings::class.java.declaredFields.any { it.name.contains("screenKeepMode") })
    }

    @Test
    fun `the settings line of the ride log says both and the change is said`() {
        val changed = REauSettings(screenKeep = false, screenKeepLockUi = true)
        assertTrue(" screenKeep=true screenKeepLockUi=false" in SettingsInUse.line(REauSettings()))
        assertTrue("screenKeep=false screenKeepLockUi=true" in SettingsInUse.line(changed))
        assertEquals(listOf("screenKeep: true -> false", "screenKeepLockUi: false -> true"), REauSettings().changesTo(changed))
    }

    @Test
    fun `the group is in the settings screen, with the lock screen row only while the switch is on, and no selector`() {
        val group = File("src/main/java/com/reau/app/ui/SettingsScreenKeep.kt").readText()
        assertTrue("{ change(s.copy(screenKeep = it)) }, \"screen_keep\"" in group)
        assertTrue("if (s.screenKeep) {" in group.substringAfter("val rows = buildList"))
        assertTrue("{ change(s.copy(screenKeepLockUi = it)) }, \"screen_keep_lock_ui\"" in group)
        assertFalse("SegmentedRow" in group || "ScreenKeepMode" in group)
        assertTrue("ScreenKeepGroup(settings, change)" in File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText())
        assertFalse(File("src/main/java/com/reau/app/settings/ScreenKeepMode.kt").exists())
    }

    @Test
    fun `the switch is called a test in Portuguese, so its row is red, and no other row of the group is`() {
        assertEquals("Tela acesa com o celular bloqueado (teste)", string("values-pt", "screen_keep_title"))
        assertTrue(ExperimentalMark.marks(string("values-pt", "screen_keep_title"), string("values-pt", "screen_keep_sub")))
        for (name in listOf("screen_keep_lock_ui_title", "screen_keep_lock_ui_sub")) {
            assertFalse(name, ExperimentalMark.marks(string("values-pt", name)))
        }
        assertEquals(
            "Na viagem, o REau acende a tela quando ela apaga, para o celular não fechá-lo. Gasta mais bateria",
            string("values-pt", "screen_keep_sub"),
        )
    }

    @Test
    fun `every text of the function is in the three languages, and the interval texts are gone`() {
        val names = listOf(
            "group_screen_keep", "screen_keep_title", "screen_keep_sub", "screen_keep_info", "screen_keep_lock_ui_title", "screen_keep_lock_ui_sub",
            "screen_keep_lock_projecting", "screen_keep_lock_swipe", "screen_keep_lock_pause", "action_lock_screen",
        )
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in names) assertTrue("$folder $name", string(folder, name).isNotBlank())
            assertFalse("$folder: no interval text", File("src/main/res/$folder/strings.xml").readText().contains("screen_keep_mode"))
        }
        assertEquals("Mostrar o REau sobre o bloqueio", string("values-pt", "screen_keep_lock_ui_title"))
        assertEquals("Projetando no painel", string("values-pt", "screen_keep_lock_projecting"))
        assertEquals("Deslize para cima para desbloquear", string("values-pt", "screen_keep_lock_swipe"))
        assertEquals("Apagar a tela nesta viagem", string("values-pt", "screen_keep_lock_pause"))
        assertEquals("Bloquear a tela", string("values-pt", "action_lock_screen"))
    }
}
