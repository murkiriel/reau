package com.reau.app.settings

import com.reau.app.aa.AaConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The debug build's experiment "Android Auto in motorcycle mode" ([REauSettings.experimentMotorcycle]): off by default; on, and only in a debug build, it reaches
 * the Android Auto configuration ([AaConfig.motorcycle]); a release build has it off whatever was stored. It is stored on its own, known by the start guard, said in
 * the ride log, counted from the next Android Auto session (like the other connection experiments), and shown in the settings in the three languages. What the
 * response says is MotorcycleAnnouncementTest's.
 */
class MotorcycleSwitchTest {
    private val on = REauSettings(experimentMotorcycle = true)

    @Test
    fun `it is off by default, in a debug build and in a release`() {
        assertFalse(REauSettings().experimentMotorcycle)
        assertFalse(REauSettings().aaConfig.motorcycle)
        for (debug in listOf(true, false)) assertFalse("debug=$debug", REauSettings().sanitized(debug).aaConfig.motorcycle)
    }

    @Test
    fun `on, in a debug build, it reaches the Android Auto configuration and nothing else in it moves`() {
        val s = on.sanitized(debugBuild = true)
        assertTrue(s.experimentMotorcycle)
        assertTrue(s.aaConfig.motorcycle)
        assertEquals(REauSettings().sanitized(true).aaConfig, s.aaConfig.copy(motorcycle = false))
    }

    @Test
    fun `in a release build it is always off, whatever was stored`() {
        val s = on.sanitized(debugBuild = false)
        assertFalse(s.experimentMotorcycle)
        assertFalse(s.aaConfig.motorcycle)
        assertEquals(REauSettings().sanitized(false), s)
    }

    @Test
    fun `it is stored and read back on its own, and a phone that never stored it reads it off`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertFalse(store.load().experimentMotorcycle)
        store.save(on)
        // the store sanitizes with the build it runs in: on in a debug build, off in a release
        assertEquals(com.reau.app.BuildConfig.DEBUG, store.load().experimentMotorcycle)
        assertEquals(on.sanitized(), store.load())
        store.save(REauSettings())
        assertFalse(store.load().experimentMotorcycle)
        val old = KeystoreBackedPrefsTest.FakePrefs()
        old.edit().putInt("density", 200).apply()
        assertFalse(SettingsStore(old).load().experimentMotorcycle)
    }

    @Test
    fun `the start guard knows it and switches it off`() {
        assertEquals(listOf("experimentMotorcycle"), ExperimentsGuard.experimentsOn(on))
        assertEquals(REauSettings(), ExperimentsGuard.allOff(on))
        assertTrue(ExperimentsGuard.armed(on))
    }

    @Test
    fun `the ride log says it, in the settings line and in the experiments line, with the vehicle id, and a change of it is told`() {
        assertTrue("experimentMotorcycle=false" in SettingsInUse.line(REauSettings()))
        assertTrue("experimentMotorcycle=true" in SettingsInUse.line(on))
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        val said = SettingsInUse.experimentsLine(on)!!
        assertTrue(said, "head unit announced to Android Auto as a motorcycle (vehicle id reau-moto-1)" in said)
        assertEquals(listOf("experimentMotorcycle: false -> true"), REauSettings().changesTo(on))
    }

    @Test
    fun `a change of it counts from the next Android Auto session, as the other connection experiments do`() {
        // it restarts nothing: no step of the running projection reads it
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings(), on))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(REauSettings().sanitized(true), on.sanitized(true)))
        // a running session keeps what it was told when it began, even when it is started again for another reason (the density)
        val running = REauSettings().sanitized(true).aaConfig
        assertFalse(LiveTuning.sessionConfig(running, on.sanitized(true).copy(density = 200)).motorcycle)
        assertTrue(LiveTuning.sessionConfig(running.copy(motorcycle = true), REauSettings().copy(density = 200)).motorcycle)
        assertEquals(200, LiveTuning.sessionConfig(running, REauSettings().copy(density = 200)).density)
        // and the receiver is given the settings' configuration when the projection begins
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(sides.contains("aaConfig = settings.aaConfig,"))
    }

    @Test
    fun `the row is shown in a debug build only, in the connection group, and has its title and line in the three languages, the line saying Experimental`() {
        val titles = mapOf(
            "values" to "Android Auto in motorcycle mode",
            "values-pt" to "Android Auto em modo moto",
            "values-es" to "Android Auto en modo moto",
        )
        for ((folder, title) in titles) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", xml.contains("<string name=\"exp_motorcycle_title\">$title</string>"))
            val sub = Regex("""<string name="exp_motorcycle_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder line", sub != null && sub.startsWith("Experimental") && sub.length > 120)
        }
        val screen = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        val group = screen.substringAfter("fun ConnectionExperimentsGroup").substringBefore("fun VideoExperimentsGroup")
        assertTrue(Regex("""s\.experimentMotorcycle, \{ change\(s\.copy\(experimentMotorcycle = it\)\) \}, "exp_motorcycle"""").containsMatchIn(group))
        assertTrue("only in a debug build", group.contains("if (com.reau.app.BuildConfig.DEBUG) it + motorcycleRow else it"))
    }
}
