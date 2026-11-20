package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Fundo desfocado nas bordas" went on 20/11/2026 (the rider found it ugly): the setting, the row, the texts, the live step and the whole drawing
 * path (the small copy, the blur shader, the framebuffer) are gone, and the border the fit leaves is black as it was. A phone that saved the switch on keeps
 * its old key in the file: it is ignored on load, goes at the next save, and nothing else the phone saved is lost.
 */
class BlurredFillGoneTest {
    private fun text(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the settings have no such field and the line of the settings in use does not say it`() {
        assertFalse("blurredFill" in REauSettings::class.java.declaredFields.map { it.name })
        assertFalse("blurredFill" in SettingsInUse.LOGGED)
        assertFalse("blurredFill" in SettingsInUse.line(REauSettings()))
    }

    @Test
    fun `an old value saved as on is ignored on load, does not break it, and changes nothing else`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val other = REauSettings.DENSITIES.first { it != REauSettings().density }
        SettingsStore(prefs).save(REauSettings(density = other))
        val before = SettingsStore(prefs).load()
        prefs.edit().putBoolean("blurred_fill", true).apply()
        val loaded = SettingsStore(prefs).load()
        assertEquals("the same settings as without the old key", before, loaded)
        assertEquals(other, loaded.density)
        assertTrue("the mask, on by default, is not turned off by the old key", loaded.dashMask)
        assertEquals("nothing of it is in what the app uses", loaded, loaded.sanitized())
        assertTrue("the old key lies in the file until the next save", prefs.contains("blurred_fill"))
        SettingsStore(prefs).save(loaded)
        assertFalse("and the save lets it go", prefs.contains("blurred_fill"))
        assertEquals(loaded, SettingsStore(prefs).load())
    }

    @Test
    fun `no old value turns anything on in the live tuning, the screen or the compositor`() {
        assertEquals(emptyList<String>(), com.reau.app.service.LiveTuning.Step.entries.map { it.name }.filter { "BLUR" in it })
        for (path in listOf("settings/REauSettings.kt", "settings/SettingsStore.kt", "service/LiveSides.kt", "service/LiveTuning.kt", "ui/FitPanel.kt", "video/DashCompositor.kt", "video/CompositorGl.kt", "video/CompositorMath.kt")) {
            val body = text(path).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
            assertFalse("$path still has the blurred fill", Regex("""[Bb]lurredFill|BLURRED_FILL|[Bb]ackdrop|BACKDROP""").containsMatchIn(body.replace("OLD_KEY_BLURRED_FILL", "").replace("\"blurred_fill\"", "")))
        }
    }

    @Test
    fun `the texts of the switch are gone in the three languages`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder", Regex("""name="blurred_fill_""").containsMatchIn(strings))
        }
    }
}
