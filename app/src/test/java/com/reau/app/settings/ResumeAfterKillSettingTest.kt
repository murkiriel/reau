package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Retomar se o sistema fechar o REau" ([REauSettings.resumeAfterKill]), plan 34 item 4: on by default, kept on its own key, in the ride log's settings line,
 * read by the service when the system restarts it (nothing to restart for a change), on the settings screen in the four languages. What it does is
 * [com.reau.app.service.ProjectionResumeTest]'s.
 */
class ResumeAfterKillSettingTest {
    private val off = REauSettings(resumeAfterKill = false)

    @Test
    fun `it is on by default, on a new install, with nothing stored and after sanitizing`() {
        assertTrue(REauSettings().resumeAfterKill)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().resumeAfterKill)
        assertTrue(REauSettings().sanitized().resumeAfterKill)
        assertFalse("sanitizing does not take the rider's choice away", off.sanitized().resumeAfterKill)
    }

    @Test
    fun `a phone that stored the other settings but never this one reads it on`() {
        val older = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).putInt("no_dash_stop_minutes", 10).apply() }
        assertTrue(SettingsStore(older).load().resumeAfterKill)
    }

    @Test
    fun `the choice is stored under its own key and read back, on and off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(off)
        assertEquals(false, prefs.map["resume_after_kill"])
        assertFalse(SettingsStore(prefs).load().resumeAfterKill)
        SettingsStore(prefs).save(REauSettings())
        assertEquals(true, prefs.map["resume_after_kill"])
        assertTrue(SettingsStore(prefs).load().resumeAfterKill)
    }

    @Test
    fun `a file that holds the key off is read off, whatever the other settings`() {
        val stored = KeystoreBackedPrefsTest.FakePrefs()
        stored.edit().putInt("density", 200).putBoolean("resume_after_kill", false).apply()
        assertFalse(SettingsStore(stored).load().resumeAfterKill)
    }

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue("resumeAfterKill=true" in SettingsInUse.line(REauSettings()))
        assertTrue("resumeAfterKill=false" in SettingsInUse.line(off))
        assertEquals(listOf("resumeAfterKill: true -> false"), REauSettings().changesTo(off))
        assertTrue("resumeAfterKill" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it restarts nothing, the service reads it when the system restarts it`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), off))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(off, REauSettings()))
    }

    @Test
    fun `its comment says on by default, as the code does`() {
        val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        val doc = source.substringBefore("val resumeAfterKill: Boolean = true,").substringAfterLast("/**")
        assertTrue(doc, "On by default" in doc)
    }

    @Test
    fun `the switch is on the settings screen, after the time without the dash, and applies at once`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, screen.indexOf("{ NoDashStop(s, change) },") in 0 until screen.indexOf("{ ResumeAfterKill(s, change) },"))
        val row = File("src/main/java/com/reau/app/ui/SettingsNoDashStop.kt").readText()
        assertTrue(row, "R.string.resume_after_kill_title, R.string.resume_after_kill_sub, R.string.resume_after_kill, s.resumeAfterKill, \"resume_after_kill\", now = true," in row)
        assertTrue(row, "change(s.copy(resumeAfterKill = it))" in row)
    }

    @Test
    fun `the texts are in the four languages, say the limit and the force stop, and say it is on by default`() {
        fun string(folder: String, name: String, file: String = "strings") =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/$file.xml").readText())!!.groupValues[1]
        assertEquals("Resume if the system closes REau (experimental)", string("values", "resume_after_kill_title"))
        assertEquals("Retomar se o sistema fechar o REau (experimental)", string("values-pt", "resume_after_kill_title"))
        assertEquals("Retomar si el sistema cierra REau (experimental)", string("values-es", "resume_after_kill_title"))
        assertTrue(string("values-hi", "resume_after_kill_title").endsWith("(प्रायोगिक)"))
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            val long = string(folder, "resume_after_kill")
            assertTrue("$folder: the limit", "3" in long && "30" in long)
            assertTrue("$folder: Xiaomi's force stop", "Xiaomi" in long)
            assertTrue("$folder: a sub line", string(folder, "resume_after_kill_sub").length > 30)
            assertTrue("$folder: the notification's line", string(folder, "notif_resumed", "strings_extra").isNotBlank())
        }
        // 26/11/2026: still not tried on a phone that closes the app, so the title says experimental and the (i) says it may go.
        assertTrue("On by default, but it is a test and may be removed in a later version." in string("values", "resume_after_kill"))
        assertTrue("On by default" in string("values", "resume_after_kill") && "Vem ligado" in string("values-pt", "resume_after_kill") &&
            "Viene activado" in string("values-es", "resume_after_kill"))
    }
}
