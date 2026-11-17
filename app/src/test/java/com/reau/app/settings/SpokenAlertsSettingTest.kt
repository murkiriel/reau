package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch of the spoken alerts: off by default, kept, logged with the settings in use, and in the settings screen in the three languages. */
class SpokenAlertsSettingTest {
    @Test
    fun `it comes off on a new install and with nothing stored`() {
        assertEquals(false, REauSettings().spokenAlerts)
        assertEquals(false, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().spokenAlerts)
        assertEquals(false, REauSettings().sanitized().spokenAlerts)
    }

    @Test
    fun `the rider's choice is kept`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(spokenAlerts = true))
        assertEquals(true, SettingsStore(prefs).load().spokenAlerts)
        SettingsStore(prefs).save(REauSettings(spokenAlerts = false))
        assertEquals(false, SettingsStore(prefs).load().spokenAlerts)
    }

    @Test
    fun `the settings line of the ride log says whether it was on`() {
        assertTrue("spokenAlerts=false" in SettingsInUse.line(REauSettings()))
        assertTrue("spokenAlerts=true" in SettingsInUse.line(REauSettings(spokenAlerts = true)))
        assertEquals(listOf("spokenAlerts: false -> true"), REauSettings().changesTo(REauSettings(spokenAlerts = true)))
    }

    @Test
    fun `the switch is in the settings screen, with its name and its descriptions in the three languages`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, "SettingSwitch(R.string.spoken_alerts_title, R.string.spoken_alerts_sub, R.string.spoken_alerts, s.spokenAlerts, \"spoken_alerts\"" in screen)
        assertTrue("change(s.copy(spokenAlerts = it))" in screen)
        fun string(folder: String, name: String) =
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
        assertEquals("Spoken alerts in the headset", string("values", "spoken_alerts_title"))
        assertEquals("Avisos falados no fone", string("values-pt", "spoken_alerts_title"))
        assertEquals("Avisos hablados en el auricular", string("values-es", "spoken_alerts_title"))
        assertTrue(string("values-pt", "spoken_alerts").startsWith("Fala os avisos importantes pela voz do Android, no fone do capacete, como a voz da navegação"))
        assertTrue("says it is off by default", "Off by default" in string("values", "spoken_alerts") && "Vem desligado" in string("values-pt", "spoken_alerts") &&
            "Viene apagado" in string("values-es", "spoken_alerts"))
    }
}
