package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.stream.VideoLoop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch "Teto do quadro-chave para o painel (experimental)" ([REauSettings.keyframeCap], the owner's decision of 28/11/2026): on by default, kept under its own key, in the
 * ride log's settings line, read when the stream's encoder is built, on the settings screen in the three languages. What it does to the bitrate is
 * [com.reau.app.dash.video.KeyframeCapTest]'s and [com.reau.app.dash.video.KeyframeCapIsWiredTest]'s.
 */
class KeyframeCapSettingTest {
    private val off = REauSettings(keyframeCap = false)

    private fun string(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    @Test
    fun `it is on by default, on a new install, with nothing stored and after sanitizing`() {
        assertTrue(REauSettings().keyframeCap)
        assertTrue(SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().keyframeCap)
        assertTrue(REauSettings().sanitized().keyframeCap)
        assertFalse("sanitizing does not take the rider's choice away", off.sanitized().keyframeCap)
    }

    @Test
    fun `a phone that stored the other settings but never this one reads it on`() {
        val older = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).putBoolean("pace_packets", false).apply() }
        assertTrue(SettingsStore(older).load().keyframeCap)
    }

    @Test
    fun `the choice is stored under its own key and read back, on and off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(off)
        assertEquals(false, prefs.map["keyframe_cap"])
        assertFalse(SettingsStore(prefs).load().keyframeCap)
        SettingsStore(prefs).save(REauSettings())
        assertEquals(true, prefs.map["keyframe_cap"])
        assertTrue(SettingsStore(prefs).load().keyframeCap)
    }

    @Test
    fun `it does not touch the key of the packet spacing, the switch beside it`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(pacePackets = false, keyframeCap = true))
        assertEquals(false, prefs.map["pace_packets"])
        assertEquals(true, prefs.map["keyframe_cap"])
        assertFalse(SettingsStore(prefs).load().pacePackets)
        assertTrue(SettingsStore(prefs).load().keyframeCap)
    }

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue("keyframeCap=true" in SettingsInUse.line(REauSettings()))
        assertTrue("keyframeCap=false" in SettingsInUse.line(off))
        assertEquals(listOf("keyframeCap: true -> false"), REauSettings().changesTo(off))
        assertTrue("keyframeCap" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it restarts nothing, the stream's encoder reads it when it is built`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), off))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(off, REauSettings()))
    }

    @Test
    fun `the stream takes it from the rider's settings, on or off`() {
        assertTrue(VideoLoop.Settings().withRider(REauSettings()).keyframeCap)
        assertFalse(VideoLoop.Settings().withRider(off).keyframeCap)
    }

    @Test
    fun `its comment says on by default, as the code does`() {
        val source = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        val doc = source.substringBefore("val keyframeCap: Boolean = true,").substringAfterLast("/**")
        assertTrue(doc, "On by default" in doc)
        assertTrue(doc, "48 kB" in doc)
    }

    @Test
    fun `the switch is on the settings screen, after the packet spacing one, with no now chip because it counts from the next stream`() {
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertTrue(screen, screen.indexOf("{ PacePackets(s, change) },") in 0 until screen.indexOf("{ KeyframeCapSwitch(s, change) },"))
        val row = File("src/main/java/com/reau/app/ui/SettingsNoDashStop.kt").readText().substringAfter("internal fun KeyframeCapSwitch(").substringBefore("/** 2 ms")
        assertTrue(row, "R.string.keyframe_cap_title, R.string.keyframe_cap_sub, R.string.keyframe_cap, s.keyframeCap, \"keyframe_cap\") {" in row)
        assertFalse("it does not apply at once", "now = true" in row)
        assertTrue(row, "change(s.copy(keyframeCap = it))" in row)
    }

    @Test
    fun `the texts are in the three languages, say the 48 kB and that it is on by default`() {
        assertEquals("Cap the keyframe size for the dash (experimental)", string("values", "keyframe_cap_title"))
        assertEquals("Teto do quadro-chave para o painel (experimental)", string("values-pt", "keyframe_cap_title"))
        assertEquals("Tope del cuadro clave para el tablero (experimental)", string("values-es", "keyframe_cap_title"))
        for (folder in listOf("values", "values-pt", "values-es")) {
            assertTrue("$folder: the 48 kB in the long text", "48 kB" in string(folder, "keyframe_cap"))
            assertTrue("$folder: the 48 kB in the sub line", "48 kB" in string(folder, "keyframe_cap_sub"))
        }
        assertTrue(
            "On by default" in string("values", "keyframe_cap") && "Vem ligado" in string("values-pt", "keyframe_cap") &&
                "Viene activado" in string("values-es", "keyframe_cap"),
        )
    }
}
