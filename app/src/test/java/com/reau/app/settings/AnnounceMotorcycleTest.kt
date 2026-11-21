package com.reau.app.settings

import com.reau.app.aa.AaConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.ui.ExperimentalMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Anunciar como moto" ([REauSettings.announceMotorcycle]), the plain setting that an experiment of debug builds (`experimentMotorcycle`) became on 20/11/2026:
 * on by default, in a debug build and in a release alike; stored on its own key (the experiment's old key is ignored: everyone starts with it on); reaching the Android Auto configuration ([AaConfig.motorcycle]); counted from the next Android Auto session; in the Android Auto group
 * of the settings, in the three languages, without the word Experimental. What the response says is MotorcycleAnnouncementTest's.
 */
class AnnounceMotorcycleTest {
    private val off = REauSettings(announceMotorcycle = false)

    private fun store(prefs: KeystoreBackedPrefsTest.FakePrefs = KeystoreBackedPrefsTest.FakePrefs()) = SettingsStore(prefs)

    private fun prefsWith(vararg entries: Pair<String, Boolean>) = KeystoreBackedPrefsTest.FakePrefs().also { p ->
        p.edit().putInt("density", 200).apply()
        entries.forEach { (key, value) -> p.edit().putBoolean(key, value).apply() }
    }

    // ── on by default, in both builds ────────────────────────────────────────────────────────────────────

    @Test
    fun `it is on by default, in the settings and in the Android Auto configuration`() {
        assertTrue(REauSettings().announceMotorcycle)
        assertTrue(REauSettings().aaConfig.motorcycle)
        for (debug in listOf(true, false)) {
            assertTrue("debug=$debug", REauSettings().sanitized(debug).announceMotorcycle)
            assertTrue("debug=$debug", REauSettings().sanitized(debug).aaConfig.motorcycle)
        }
    }

    @Test
    fun `it is available in a release build too - sanitized keeps the rider's choice, on or off, in both builds`() {
        for (debug in listOf(true, false)) {
            assertTrue("on, debug=$debug", REauSettings(announceMotorcycle = true).sanitized(debug).announceMotorcycle)
            assertFalse("off, debug=$debug", off.sanitized(debug).announceMotorcycle)
            assertFalse("off, debug=$debug", off.sanitized(debug).aaConfig.motorcycle)
        }
    }

    @Test
    fun `on, it reaches the Android Auto configuration and nothing else in it moves`() {
        val on = REauSettings().sanitized(true)
        assertTrue(on.aaConfig.motorcycle)
        assertEquals(off.sanitized(true).aaConfig.copy(motorcycle = true), on.aaConfig)
        assertEquals(AaConfig(), AaConfig().copy(motorcycle = false))
        assertFalse("the configuration alone says car, as it always did", AaConfig().motorcycle)
    }

    // ── the stored value ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a phone that never stored it reads it on`() {
        assertTrue("empty", store().load().announceMotorcycle)
        assertTrue("an older phone", store(prefsWith()).load().announceMotorcycle)
    }

    @Test
    fun `it is stored and read back on its own key, on and off`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val s = store(prefs)
        s.save(off)
        assertEquals(false, prefs.map["announce_motorcycle"])
        assertFalse(s.load().announceMotorcycle)
        s.save(REauSettings())
        assertEquals(true, prefs.map["announce_motorcycle"])
        assertTrue(s.load().announceMotorcycle)
        assertFalse("the experiment's key is not written any more", prefs.map.containsKey("experiment_motorcycle"))
    }

    @Test
    fun `the experiment's old key is ignored, off or on - everyone starts with it on`() {
        assertTrue("off was stored by the experiment", store(prefsWith("experiment_motorcycle" to false)).load().announceMotorcycle)
        assertTrue(store(prefsWith("experiment_motorcycle" to true)).load().announceMotorcycle)
    }

    @Test
    fun `once the new key is saved it is the only one that counts`() {
        val prefs = prefsWith("experiment_motorcycle" to true)
        val s = store(prefs)
        s.save(off)
        assertFalse(s.load().announceMotorcycle)
        assertEquals("the old key still lies in the file, ignored", true, prefs.map["experiment_motorcycle"])
    }

    @Test
    fun `the old keys of the other experiments are ignored - the settings come out as the defaults`() {
        val old = listOf(
            "experiment_rtp_pacing", "experiment_debug_dscp", "experiment_adaptive_bitrate", "experiment_wifi_bind", "experiment_start_guard",
            "experiment_encoder_watch", "experiment_focus_gap", "experiment_night_memory", "experiment_weather_age", "experiment_heat_ceiling",
            "experiment_gps_speed", "experiment_recalc_inactive", "experiment_color_matrix", "experiment_protocol_six", "experiment_tolerant_rx",
            "experiment_wake_aa", "experiment_encoder_low_latency",
        )
        assertEquals(store(prefsWith()).load(), store(prefsWith(*old.map { it to true }.toTypedArray())).load())
    }

    // ── the log, the live tuning ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the ride log's settings line says it, and a change of it is told`() {
        assertTrue("announceMotorcycle=true" in SettingsInUse.line(REauSettings()))
        assertTrue("announceMotorcycle=false" in SettingsInUse.line(off))
        assertEquals(listOf("announceMotorcycle: true -> false"), REauSettings().changesTo(off))
        assertTrue("announceMotorcycle" in SettingsInUse.LOGGED)
    }

    @Test
    fun `a change of it counts from the next Android Auto session - it restarts nothing and a running session keeps what it was told`() {
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), off))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(off, REauSettings()))
        // a running session keeps what it was told when it began, even when it is started again for another reason (the density)
        val running = off.sanitized(true).aaConfig
        assertFalse(LiveTuning.sessionConfig(running, REauSettings().copy(density = 200)).motorcycle)
        assertTrue(LiveTuning.sessionConfig(running.copy(motorcycle = true), off.copy(density = 200)).motorcycle)
        assertEquals(200, LiveTuning.sessionConfig(running, REauSettings().copy(density = 200)).density)
        // and the receiver is given the settings' configuration when the projection begins
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(sides.contains("aaConfig = settings.aaConfig,"))
    }

    // ── the screen and its texts ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the row is in the Android Auto group in every build, not behind a debug test, and the group is on the settings screen`() {
        val group = File("src/main/java/com/reau/app/ui/SettingsAndroidAuto.kt").readText()
        assertTrue(Regex("""s\.announceMotorcycle, \{ change\(s\.copy\(announceMotorcycle = it\)\) \}, "announce_motorcycle"""").containsMatchIn(group))
        assertFalse("no build test", group.contains("BuildConfig"))
        assertTrue(group.contains("tag = \"group_aa_head_unit\""))
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        assertEquals(1, Regex("""AndroidAutoGroup\(settings, change\)""").findAll(screen).count())
        assertTrue("before the debug group", screen.indexOf("AndroidAutoGroup(settings, change)") < screen.indexOf("DebugGroup(settings, change, actions, say)"))
    }

    @Test
    fun `the texts are in the three languages, are plain (no Experimental, no red) and say it counts from the next start`() {
        val titles = mapOf("values" to "Announce as a motorcycle", "values-pt" to "Anunciar como moto", "values-es" to "Anunciar como moto")
        for ((folder, title) in titles) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", xml.contains("<string name=\"announce_motorcycle_title\">$title</string>"))
            val sub = Regex("""<string name="announce_motorcycle_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertNotNull("$folder line", sub)
            assertTrue("$folder line is long enough to say it", sub!!.length > 120)
            assertFalse("$folder: not experimental", ExperimentalMark.marks(title, sub))
            assertFalse(folder, sub.contains("xperiment"))
            assertTrue("$folder group", xml.contains("<string name=\"group_aa_head_unit\">Android Auto</string>"))
            assertFalse("$folder: the old row's texts are gone", xml.contains("exp_motorcycle"))
        }
        assertNull(Regex("""name="announce_motorcycle_sub">[^<]*Experimental""").find(File("src/main/res/values/strings.xml").readText()))
    }
}
