package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.ui.ExperimentalMark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one switch "Modo de bloqueio do REau (teste)" of the 0.3.96 test build: off by default, kept under a key of its own (what the 0.3.9x builds saved of the three
 * earlier choices is never read), logged, and in the settings screen in the three languages.
 */
class ScreenKeepSettingTest {
    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `the switch comes off on a new install and with nothing stored`() {
        for (s in listOf(REauSettings(), SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load(), REauSettings().sanitized())) assertFalse(s.lockMode)
    }

    @Test
    fun `the rider's choice is kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(lockMode = true))
        assertTrue(SettingsStore(prefs).load().lockMode)
        SettingsStore(prefs).save(REauSettings(lockMode = false))
        assertFalse(SettingsStore(prefs).load().lockMode)
    }

    @Test
    fun `what the 0_3_9x builds saved of the earlier choices never turns it on, whatever it was`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        for (key in listOf("screen_keep", "screen_keep_on", "screen_keep_lock_ui")) prefs.map[key] = true
        prefs.map["screen_keep_mode"] = "EVERY_60_S"
        assertFalse("the old trues do not hold it on", SettingsStore(prefs).load().lockMode)
        prefs.map["lock_mode_on"] = true
        assertTrue("only its own key does", SettingsStore(prefs).load().lockMode)
        val names = REauSettings::class.java.declaredFields.map { it.name }
        assertFalse("no field of the earlier choices is left", names.any { it.contains("screenKeep") })
    }

    @Test
    fun `the settings line of the ride log says the value and the change is said`() {
        assertTrue(" lockMode=false" in SettingsInUse.line(REauSettings()))
        assertTrue("lockMode=true" in SettingsInUse.line(REauSettings(lockMode = true)))
        assertEquals(listOf("lockMode: false -> true"), REauSettings().changesTo(REauSettings(lockMode = true)))
        assertTrue("lockMode" in SettingsInUse.LOGGED)
    }

    @Test
    fun `the group is in the settings screen with the one switch, and no selector or second switch`() {
        val group = File("src/main/java/com/reau/app/ui/SettingsScreenKeep.kt").readText()
        assertTrue("{ change(s.copy(lockMode = it)) }, \"lock_mode\"" in group)
        assertFalse("SegmentedRow" in group || "ScreenKeepMode" in group || "screenKeep" in group || "if (s.lockMode)" in group)
        assertEquals("one row", 1, Regex("""SwitchRow\(""").findAll(group).count())
        assertTrue("ScreenKeepGroup(settings, change)" in File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText())
        assertFalse(File("src/main/java/com/reau/app/settings/ScreenKeepMode.kt").exists())
    }

    @Test
    fun `the switch is called a test in Portuguese, so its row is red, and says what it does in a short line`() {
        assertEquals("Modo de bloqueio do REau (teste)", string("values-pt", "lock_mode_title"))
        assertTrue(ExperimentalMark.marks(string("values-pt", "lock_mode_title"), string("values-pt", "lock_mode_sub")))
        assertEquals(
            "Mostra o botão Bloquear a tela, acende a tela quando ela apaga e mostra o REau sobre o bloqueio. Gasta mais bateria",
            string("values-pt", "lock_mode_sub"),
        )
    }

    @Test
    fun `every text of the function is in the three languages, and the texts of the old switches are gone`() {
        val names = listOf(
            "group_screen_keep", "lock_mode_title", "lock_mode_sub", "lock_mode_info", "screen_keep_lock_projecting", "screen_keep_lock_swipe",
            "screen_keep_lock_pause", "action_lock_screen",
        )
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in names) assertTrue("$folder $name", string(folder, name).isNotBlank())
            val all = File("src/main/res/$folder/strings.xml").readText()
            for (gone in listOf("screen_keep_title", "screen_keep_sub", "screen_keep_info", "screen_keep_lock_ui", "screen_keep_mode")) assertFalse("$folder $gone", gone in all)
        }
        assertEquals("Projetando no painel", string("values-pt", "screen_keep_lock_projecting"))
        assertEquals("Toque para desbloquear", string("values-pt", "screen_keep_lock_swipe"))
        assertEquals("Apagar a tela nesta viagem", string("values-pt", "screen_keep_lock_pause"))
        assertEquals("Bloquear a tela", string("values-pt", "action_lock_screen"))
    }
}
