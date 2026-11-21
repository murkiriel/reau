package com.reau.app.stream

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.settings.REauSettings
import com.reau.app.stream.StreamGovernor.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 27, layer 1: with the settings the app really runs with, the fps ladder (`AutoFpsController`) could not be reached. It never
 * climbed and never came down, because the ceiling the settings fix (4) was also the ladder's floor. These tests ran on the code as it
 * was BEFORE the ladder was cut (step G0, commit bcb027e), and are what justified cutting it: what went away did not run.
 *
 * With the cut (step G1, 14/11/2026) the tests of the fixed settings and of the heat ceiling stay as they were. What named
 * `AutoFpsController` went to the archive with it (`reau_arquivo/governador-escada-20261114/`): the test of the ladder at 4 of 4 over
 * 100 000 windows, and the one line of the heat test that held the plan at 4 of 4. The last test keeps its checks on the governor; of the
 * text in brackets it now holds the three the cut left (`ok`, `not judged`, `short: …`) where it held the ladder's states.
 */
class EscadaInalcancavelTest {
    private val extremes = listOf(0, 2, 6, 16, 30, 99)

    @Test
    fun `whatever was stored, the settings in use ask for 6 fps, 400 kbps constant, a keyframe a second and both profiles, pessimistic`() {
        val stored = listOf(REauSettings()) + extremes.flatMap { v ->
            listOf(
                REauSettings(fpsCeiling = v),
                REauSettings(keyframeSec = v),
                REauSettings(streamBitrateBps = v * 1000),
                REauSettings(clockSlowPercent = v),
                REauSettings(
                    fpsCeiling = v, keyframeSec = v, streamBitrateBps = v * 100_000, clockSlowPercent = v, bitrateMode = BitrateMode.VBR,
                    maneuverFpsProfile = false, speedFpsProfile = false, maneuverData = false, navProfileMode = NavProfileMode.OTIMISTA,
                ),
            )
        }
        for (settings in stored) {
            for (debug in listOf(true, false)) {
                val s = settings.sanitized(debugBuild = debug)
                val said = "stored $settings"
                // The video is fixed at 6 fps and 400 kbps in both builds (FixedVideoStoreTest).
                assertEquals(said, 6, s.fpsCeiling)
                assertEquals(said, 400_000, s.streamBitrateBps)
                assertEquals(said, DashConfig.VIDEO_FRAME, s.frameSize)
                assertEquals(said, 1, s.keyframeSec)
                assertEquals(said, BitrateMode.CBR, s.bitrateMode)
                assertEquals(said, 0, s.clockSlowPercent)
                assertTrue(said, s.maneuverFpsProfile && s.speedFpsProfile && s.maneuverData)
                assertEquals(said, NavProfileMode.PESSIMISTA, s.navProfileMode)

                val stream = VideoLoop.Settings().withRider(s)
                assertEquals(said, s.fpsCeiling, stream.ceilingFps)
                assertEquals(said, 1, stream.keyframeSec)
                assertEquals(said, s.streamBitrateBps, stream.bitrateBps)
                assertEquals(said, 6, stream.lightScreenFps)
            }
        }
    }

    @Test
    fun `the stored settings pass through sanitized on the way in and on the way out`() {
        val store = File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText()
        val load = store.substringAfter("fun load(): REauSettings {").substringBefore("\n    }\n")
        assertTrue("SettingsStore.load() must end with .sanitized()", load.trimEnd().endsWith(".sanitized()"))
        assertTrue("SettingsStore.save() must store settings.sanitized()", Regex("""fun save\(settings: REauSettings\) \{\s*val s = settings\.sanitized\(\)""").containsMatchIn(store))
    }

    @Test
    fun `at a ceiling of 6 a cool phone does not lower it, both paces are within it, and a hot one lowers it and says so`() {
        for (status in -1..1) {
            assertEquals("status $status", 6, ThermalCeiling.capFor(status, 6))
            assertTrue("3 fps at status $status", ThermalCeiling.allows(status, 3, 6))
            assertTrue("6 fps at status $status", ThermalCeiling.allows(status, 6, 6))
            assertFalse("the start of a stream never says the heat capped it", VideoLoop.thermalCapped(ThermalCeiling.capFor(status, 6), 6))
        }
        for (status in 2..6) {
            assertTrue("status $status lowers the ceiling", ThermalCeiling.capFor(status, 6) < 6)
            // 6 * 5 / 8 = 3 and 6 / 2 = 3 are under the dash's floor: the snap takes them to 4, not to 3 (and 4 of 4 stays 4: ThermalCeilingTest).
            assertTrue("status $status stays at the 4 fps floor or above", ThermalCeiling.capFor(status, 6) >= DashConfig.MIN_STREAM_FPS)
            assertTrue("and says so", VideoLoop.thermalCapped(ThermalCeiling.capFor(status, 6), 6))
            assertFalse("6 fps is no longer allowed at status $status", ThermalCeiling.allows(status, 6, 6))
        }
    }

    @Test
    fun `the governor with the real settings never asks for a rebuild in a poll, and its rates never leave 3 and 6`() {
        var windows = 0
        val paces = HashSet<Int>()
        val states = HashSet<String>()
        for (config in GovernorConfig.REAL) {
            for (seed in 1..SEEDS) {
                var poll = 0
                GovernorTrace.record(config, GovernorSequences.of(seed)) { bench, actions ->
                    poll++
                    val g = bench.governor
                    val said = "${config.name}, seed $seed, poll $poll (${bench.nowMs} ms): $actions"
                    assertTrue(said, actions.none { it is Action.Rebuild })
                    assertEquals(said, 6, g.encoderFps)
                    assertTrue(said, g.fps in RealRates.PACES && g.profileFps in setOf(3, 6))
                    paces += g.fps
                    assertEquals(said, 1, g.keyframeSec)
                    assertEquals(said, 1, g.encoderKeyframeSec)
                    assertEquals(said, 6, g.gopFps)
                    assertEquals(said, DashLogic.effectiveActiveBitrate(config.bitrateBps, g.encoderFps), g.activeBitrateBps) // the 200 kbps experiment is raised to the 300 that gives a 6 fps frame its least bits
                    for (line in actions.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith(GovernorTrace.WINDOW_LINE) }) {
                        windows++
                        assertEquals(said, 6, GovernorTrace.autoFpsOf(line))
                        val state = GovernorTrace.ladderStateOf(line).orEmpty()
                        assertTrue(said, state == "ok" || state == "not judged" || state.startsWith("short: "))
                        states += state.substringBefore(' ')
                        assertTrue(said, " kf=1s " in line)
                    }
                }
            }
        }
        assertTrue("the sequences closed windows: $windows", windows > SEEDS * 100)
        assertEquals("the sequences ran at every pace the stream has", RealRates.PACES, paces)
        // The windows were of every kind the bracket has a text for: whole, not full, and short.
        assertEquals("texts seen in the bracket", setOf("ok", "not", "short:"), states)
    }

    private companion object {
        const val SEEDS = 40
    }
}
