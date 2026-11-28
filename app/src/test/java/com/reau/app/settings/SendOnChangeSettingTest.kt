package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Mandar ao painel só o que mudou (experimental)" ([REauSettings.sendOnChange], the owner's decision of 28/11/2026): on by default, kept under its own key, in the ride
 * log's settings line, read when the session opens its sockets, on the settings screen in the three languages. What it does on the wire is
 * [com.reau.app.dash.SendOnChangeTest]'s and [com.reau.app.dash.DashSessionSendOnChangeTest]'s.
 */
class SendOnChangeSettingTest {
    private val off = REauSettings(sendOnChange = false)

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `it is on by default, on a new install, with nothing stored and after sanitizing`() {
        assertTrue(REauSettings().sendOnChange)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().sendOnChange)
        assertTrue(REauSettings().sanitized().sendOnChange)
        assertFalse("sanitizing does not take the rider's choice away", off.sanitized().sendOnChange)
    }

    @Test
    fun `a phone that stored the other settings but never this one reads it on`() {
        val older = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).putBoolean("keyframe_cap", false).apply() }
        assertTrue(SettingsStore(older).load().sendOnChange)
    }

    @Test
    fun `the choice is stored under its own key and read back, on and off, and the key beside it is not touched`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(off.copy(keyframeCap = true, pacePackets = false))
        assertEquals(false, prefs.map["send_on_change"])
        assertEquals(true, prefs.map["keyframe_cap"])
        assertEquals(false, prefs.map["pace_packets"])
        assertFalse(SettingsStore(prefs).load().sendOnChange)
        SettingsStore(prefs).save(REauSettings())
        assertEquals(true, prefs.map["send_on_change"])
        assertTrue(SettingsStore(prefs).load().sendOnChange)
    }

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue(" sendOnChange=true" in SettingsInUse.line(REauSettings()))
        assertTrue(" sendOnChange=false" in SettingsInUse.line(off))
        assertEquals(listOf("sendOnChange: true -> false"), REauSettings().changesTo(off))
        assertTrue("sendOnChange" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it restarts nothing, the session reads it when it opens its sockets`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), off))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(off, REauSettings()))
    }

    @Test
    fun `the service gives the session the rider's choice, read each time the session opens its sockets, and the session's own default is the old behaviour`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "sendOnChange = { runCatching { SettingsStore.open(appContext).load().sendOnChange }.getOrDefault(true) }," in live)
        val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        assertTrue("private val sendOnChange: () -> Boolean = { false }," in session)
        assertTrue("change.begin(runCatching(sendOnChange).getOrDefault(false))" in session)
    }

    @Test
    fun `its comment says on by default, as the code does`() {
        val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        val doc = source.substringBefore("val sendOnChange: Boolean = true,").substringAfterLast("/**")
        assertTrue(doc, "On by default" in doc)
        assertTrue(doc, "only the video is periodic" in doc || "the one periodic send" in doc)
    }

    @Test
    fun `the switch is on the settings screen, after the keyframe cap one, with no now chip because it counts from the next projection`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, screen.indexOf("{ KeyframeCapSwitch(s, change) },") in 0 until screen.indexOf("{ SendOnChangeSwitch(s, change) },"))
        val row = File("src/main/java/com/reau/app/ui/SettingsNoDashStop.kt").readText().substringAfter("internal fun SendOnChangeSwitch(").substringBefore("/** 2 ms")
        assertTrue(row, "R.string.send_on_change_title, R.string.send_on_change_sub, R.string.send_on_change, s.sendOnChange, \"send_on_change\") {" in row)
        assertFalse("it does not apply at once", "now = true" in row)
        assertTrue(row, "change(s.copy(sendOnChange = it))" in row)
    }

    @Test
    fun `the texts are in the three languages, are marked as a test and say it is on by default`() {
        assertEquals("Send the dash only what changed (experimental)", string("values", "send_on_change_title"))
        assertEquals("Mandar ao painel só o que mudou (experimental)", string("values-pt", "send_on_change_title"))
        assertEquals("Mandar al tablero solo lo que cambió (experimental)", string("values-es", "send_on_change_title"))
        for (folder in listOf("values", "values-pt", "values-es")) {
            assertTrue("$folder: the sub line", string(folder, "send_on_change_sub").isNotBlank())
        }
        assertTrue(
            "On by default" in string("values", "send_on_change") && "Vem ligado" in string("values-pt", "send_on_change") &&
                "Viene activado" in string("values-es", "send_on_change"),
        )
    }
}
