package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Espaçar os pacotes para o painel" ([REauSettings.pacePackets]), plan 35 S (the owner's decision of 25/11/2026): on by default, kept on its own key, in the ride
 * log's settings line, read when the session opens its sockets (nothing to restart for a change), on the settings screen in the four languages. What it does to the
 * packets is [com.reau.app.dash.SendPacerTest]'s and [com.reau.app.dash.DashSocketPacingTest]'s.
 */
class PacePacketsSettingTest {
    private val off = REauSettings(pacePackets = false)

    @Test
    fun `it is on by default, on a new install, with nothing stored and after sanitizing`() {
        assertTrue(REauSettings().pacePackets)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().pacePackets)
        assertTrue(REauSettings().sanitized().pacePackets)
        assertFalse("sanitizing does not take the rider's choice away", off.sanitized().pacePackets)
    }

    @Test
    fun `a phone that stored the other settings but never this one reads it on`() {
        val older = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).putBoolean("resume_after_kill", false).apply() }
        assertTrue(SettingsStore(older).load().pacePackets)
    }

    @Test
    fun `the choice is stored under its own key and read back, on and off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(off)
        assertEquals(false, prefs.map["pace_packets"])
        assertFalse(SettingsStore(prefs).load().pacePackets)
        SettingsStore(prefs).save(REauSettings())
        assertEquals(true, prefs.map["pace_packets"])
        assertTrue(SettingsStore(prefs).load().pacePackets)
    }

    @Test
    fun `a file that holds the key off is read off, whatever the other settings`() {
        val stored = KeystoreBackedPrefsTest.FakePrefs()
        stored.edit().putInt("density", 200).putBoolean("pace_packets", false).apply()
        assertFalse(SettingsStore(stored).load().pacePackets)
    }

    @Test
    fun `it does not touch the key of the other switches`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(off)
        assertEquals(true, prefs.map["resume_after_kill"])
        assertTrue(SettingsStore(prefs).load().resumeAfterKill)
    }

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue("pacePackets=true" in SettingsInUse.line(REauSettings()))
        assertTrue("pacePackets=false" in SettingsInUse.line(off))
        assertEquals(listOf("pacePackets: true -> false"), REauSettings().changesTo(off))
        assertTrue("pacePackets" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it restarts nothing, the sockets read it when the session opens them`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), off))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(off, REauSettings()))
    }

    @Test
    fun `its comment says on by default, as the code does`() {
        val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        val doc = source.substringBefore("val pacePackets: Boolean = true,").substringAfterLast("/**")
        assertTrue(doc, "On by default" in doc)
    }

    @Test
    fun `the switch is on the settings screen, after the resume one, with no now chip because it counts from the next projection`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, screen.indexOf("{ ResumeAfterKill(s, change) },") in 0 until screen.indexOf("{ PacePackets(s, change) },"))
        val row = File("src/main/java/com/reau/app/ui/SettingsNoDashStop.kt").readText().substringAfter("internal fun PacePackets(")
        assertTrue(row, "R.string.pace_packets_title, R.string.pace_packets_sub, R.string.pace_packets, s.pacePackets, \"pace_packets\") {" in row)
        assertFalse("it does not apply at once", "now = true" in row)
        assertTrue(row, "change(s.copy(pacePackets = it))" in row)
    }

    @Test
    fun `the texts are in the four languages, say the 2 ms and that it is on by default`() {
        fun string(folder: String, name: String) =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
        assertEquals("Space out the packets to the dash", string("values", "pace_packets_title"))
        assertEquals("Espaçar os pacotes para o painel", string("values-pt", "pace_packets_title"))
        assertEquals("Espaciar los paquetes al tablero", string("values-es", "pace_packets_title"))
        assertTrue(string("values-hi", "pace_packets_title").isNotBlank())
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            assertTrue("$folder: the 2 ms in the long text", "2 ms" in string(folder, "pace_packets"))
            assertTrue("$folder: the 2 ms in the sub line", "2 ms" in string(folder, "pace_packets_sub"))
        }
        assertTrue(
            "On by default" in string("values", "pace_packets") && "Vem ligado" in string("values-pt", "pace_packets") &&
                "Viene activado" in string("values-es", "pace_packets"),
        )
    }
}
