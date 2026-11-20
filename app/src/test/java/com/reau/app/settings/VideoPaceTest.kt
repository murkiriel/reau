package com.reau.app.settings

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.stream.VideoLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pace of profile A ([REauSettings.videoPaceFps], plan 32): the table of profiles ([DashConfig.STREAM_PROFILES]), what [REauSettings.sanitized] makes of it in a debug build
 * and in a release, how the store keeps it and reads the experiment it replaced, and how it reaches the ride log, the live tuning, the video loop and the settings screen.
 * What the governor does with it is PaceGovernorTest's.
 */
class VideoPaceTest {
    private fun at(pace: Int) = REauSettings(videoPaceFps = pace)

    // ---- the table

    @Test
    fun `the table is 4 fps at 400 kbps, 6 at 400, 8 at 600 and 12 at 800, and B is the exact half of both`() {
        assertEquals(
            listOf(listOf(4, 400_000, 2, 200_000), listOf(6, 400_000, 3, 200_000), listOf(8, 600_000, 4, 300_000), listOf(12, 800_000, 6, 400_000)),
            DashConfig.STREAM_PROFILES.map { listOf(it.fullFps, it.fullBps, it.reducedFps, it.reducedBps) },
        )
        assertEquals(listOf(4, 6, 8, 12), DashConfig.PACE_CHOICES)
        assertTrue("800 kbps is under the ceiling of every bitrate", DashConfig.STREAM_PROFILES.all { it.fullBps <= DashConfig.MAX_STREAM_BITRATE_BPS })
        assertEquals("the first is the default and the release's", DashConfig.DEFAULT_STREAM_FPS, DashConfig.STREAM_PROFILES.first().fullFps)
        assertEquals(listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS), DashConfig.STREAM_PROFILES.first().let { listOf(it.fullFps, it.fullBps) })
    }

    @Test
    fun `a pace the table does not have reads as the default`() {
        assertEquals(DashConfig.STREAM_PROFILES.first(), DashConfig.streamProfile(5))
        assertEquals(DashConfig.STREAM_PROFILES.first(), DashConfig.streamProfile(0))
        assertEquals(DashConfig.StreamProfile(8, 600_000), DashConfig.streamProfile(8))
        assertEquals(DashConfig.StreamProfile(12, 800_000, smallPicture = true), DashConfig.streamProfile(12))
    }

    @Test
    fun `the reserve's ceiling is the 6 fps profile, 400 kbps`() {
        assertEquals(6, DashConfig.RESERVE_CAP_FPS)
        assertEquals(400_000, DashConfig.RESERVE_CAP_BPS)
        assertEquals(6, DashConfig.LIGHT_SCREEN_FPS)
    }

    // ---- sanitized

    @Test
    fun `it is 4 by default, and then the profiles are 4 and 2 fps at 400 and 200 kbps as always`() {
        assertEquals(4, REauSettings().videoPaceFps)
        assertEquals(4, VideoLoop.Settings().let { DashConfig.streamProfile(it.ceilingFps.coerceAtMost(4)).fullFps })
        for (debug in listOf(true, false)) {
            val s = REauSettings().sanitized(debug)
            assertEquals("debug=$debug", listOf(4, 2, 400_000, 200_000), listOf(s.fpsCeiling, s.profileReducedFps, s.streamBitrateBps, s.profileReducedBps))
        }
    }

    @Test
    fun `in a debug build 4, 6, 8 and 12 are taken, with the ceiling, the bitrate and B from the table`() {
        for (p in DashConfig.STREAM_PROFILES) {
            val s = at(p.fullFps).sanitized(debugBuild = true)
            assertEquals(p.fullFps, s.videoPaceFps)
            assertEquals(listOf(p.fullFps, p.fullBps, p.reducedFps, p.reducedBps), listOf(s.fpsCeiling, s.streamBitrateBps, s.profileReducedFps, s.profileReducedBps))
            assertEquals(p.fullBps, s.effectiveStreamBitrateBps)
            assertEquals(BitrateMode.CBR, s.bitrateMode)
            assertEquals(DashConfig.PROFILE_KEYFRAME_SEC, s.keyframeSec)
            assertEquals("nothing else moves", REauSettings().sanitized(true), s.copy(videoPaceFps = 4, fpsCeiling = 4, streamBitrateBps = 400_000))
        }
    }

    @Test
    fun `in a debug build any other value reads as 4`() {
        for (bad in listOf(0, 1, 2, 3, 5, 7, 9, 10, 11, 13, 16, 30, -6, Int.MAX_VALUE)) {
            val s = at(bad).sanitized(debugBuild = true)
            assertEquals("pace $bad", listOf(4, 4, 400_000), listOf(s.videoPaceFps, s.fpsCeiling, s.streamBitrateBps))
        }
    }

    @Test
    fun `in a release build it is always 4, whatever was stored`() {
        for (pace in listOf(4, 6, 8, 12)) {
            val s = at(pace).sanitized(debugBuild = false)
            assertEquals("pace $pace", listOf(4, 4, 2, 400_000, 200_000), listOf(s.videoPaceFps, s.fpsCeiling, s.profileReducedFps, s.streamBitrateBps, s.profileReducedBps))
            assertEquals(REauSettings().sanitized(false), s)
        }
        assertEquals("a ceiling of 8 stored by a debug build does not survive in a release", 4, at(4).copy(fpsCeiling = 8).sanitized(false).fpsCeiling)
    }

    // ---- the store

    @Test
    fun `it is stored and read back on its own`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertEquals(4, store.load().videoPaceFps)
        store.save(at(8))
        // the store sanitizes with the build it runs in: 8 in a debug build, 4 in a release
        assertEquals(if (com.reau.app.BuildConfig.DEBUG) 8 else 4, store.load().videoPaceFps)
        assertEquals(at(8).sanitized(), store.load())
        store.save(at(6))
        assertEquals(at(6).sanitized(), store.load())
        store.save(REauSettings())
        assertEquals(4, store.load().videoPaceFps)
        assertEquals(4, store.load().fpsCeiling)
    }

    @Test
    fun `a phone that stored the experiment on reads 6, off or never stored reads 4`() {
        val on = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", true).apply() }
        assertEquals(at(6).sanitized(), SettingsStore(on).load())
        val off = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", false).apply() }
        assertEquals(4, SettingsStore(off).load().videoPaceFps)
        val none = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).apply() }
        assertEquals(4, SettingsStore(none).load().videoPaceFps)
    }

    @Test
    fun `once the pace is saved the old experiment key is not read again`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", true).apply() }
        val store = SettingsStore(prefs)
        store.save(store.load().copy(videoPaceFps = 4))
        assertEquals("the rider went back to 4 and the old key does not undo it", 4, SettingsStore(prefs).load().videoPaceFps)
        store.save(at(8))
        assertEquals(at(8).sanitized(), SettingsStore(prefs).load())
    }

    // ---- the guard, the ride log, the live tuning, the video loop

    @Test
    fun `it is no experimental switch, so the start guard has nothing to say about it`() {
        assertTrue(REauSettings::class.java.declaredFields.none { it.name == "experimentFpsSixThree" })
        assertEquals(emptyList<String>(), ExperimentsGuard.experimentsOn(at(8)))
        assertEquals(at(8), ExperimentsGuard.allOff(at(8)))
    }

    @Test
    fun `the ride log says it in the settings line, and a change of it is told`() {
        assertTrue("videoPaceFps=4" in SettingsInUse.line(REauSettings()))
        assertTrue("videoPaceFps=8" in SettingsInUse.line(at(8)))
        assertTrue("the ceiling it sets is in the line too", "fpsCeiling=8" in SettingsInUse.line(at(8).sanitized(true)))
        assertNull(SettingsInUse.experimentsLine(at(8)))
        assertEquals(listOf("videoPaceFps: 4 -> 8"), REauSettings().changesTo(at(8)))
    }

    @Test
    fun `a change of it starts the stream again, since the pace is what changes, the one to or from 12 fps too`() {
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(REauSettings().sanitized(true), at(8).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at(8).sanitized(true), at(12).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at(12).sanitized(true), at(6).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at(6).sanitized(true), at(8).sanitized(true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(at(8).sanitized(true), at(8).sanitized(true)))
    }

    @Test
    fun `it reaches the video loop with the pace, A's bitrate and B's, and in a release it is the usual one`() {
        val loop = VideoLoop.Settings()
        val off = loop.withRider(REauSettings().sanitized(true))
        assertEquals(listOf(4, 400_000, 200_000), listOf(off.ceilingFps, off.bitrateBps, off.reducedBitrateBps))
        val s = loop.withRider(at(8).sanitized(true))
        assertEquals(listOf(8, 600_000, 300_000, 6), listOf(s.ceilingFps, s.bitrateBps, s.reducedBitrateBps, s.lightScreenFps))
        assertEquals(false, s.colorMatrix)
        val release = loop.withRider(at(8).sanitized(false))
        assertEquals(listOf(4, 400_000, 200_000), listOf(release.ceilingFps, release.bitrateBps, release.reducedBitrateBps))
        val small = loop.withRider(at(12).sanitized(true))
        assertEquals(listOf(12, 800_000, 400_000), listOf(small.ceilingFps, small.bitrateBps, small.reducedBitrateBps))
    }

    @Test
    fun `the loop hands B's bitrate to the governor it builds, and builds the encoder with the governor's rate`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val start = loop.substringAfter("val g = StreamGovernor(").substringBefore("val rec = recordInto(")
        assertTrue(start, start.contains("reducedBitrateBps = settings.reducedBitrateBps"))
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec)"))
        assertTrue(loop.contains("reducedBitrateBps = rider.profileReducedBps"))
    }

    // ---- the settings screen

    @Test
    fun `the profile rows pick the pace in a debug build and show it locked in a release, with the bitrates of the table`() {
        val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        assertTrue("the chooser is a debug build's", rows.contains("if (com.reau.app.BuildConfig.DEBUG) {"))
        assertTrue(rows.contains("DashConfig.PACE_CHOICES.map { stringResource(R.string.pace_option, it) to \"pace_\$it\" }"))
        assertTrue(rows.contains("change(s.copy(videoPaceFps = DashConfig.PACE_CHOICES[it]))"))
        assertTrue(rows.contains("stringResource(R.string.profiles_fps_sub, s.fpsCeiling, s.profileReducedFps)"))
        assertTrue(rows.contains("R.string.profiles_info, s.fpsCeiling, s.streamBitrateBps / 1000,"))
        assertTrue(rows.contains("s.profileReducedFps, s.profileReducedBps / 1000,"))
        assertTrue(rows.contains("R.string.profiles_bitrate_sub, s.streamBitrateBps / 1000, s.profileReducedBps / 1000"))
        val experiments = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        assertFalse("the 6/3 experiment's row is gone", experiments.contains("fps63") || experiments.contains("experimentFpsSixThree"))
    }

    @Test
    fun `the strings are in the three languages, with the numbers as arguments and the old experiment's gone`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder still has the experiment", xml.contains("exp_fps63"))
            fun text(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(xml)?.groupValues?.get(1) ?: error("no $name in $folder")
            assertEquals("%1\$d fps", text("pace_option"))
            val sub = text("pace_sub")
            assertTrue("$folder: $sub", "%1\$d" in sub && "%2\$d" in sub && "%3\$d" in sub)
            val small = text("pace_small_sub")
            assertTrue("$folder: $small", listOf("%1\$d", "%2\$d", "%3\$d", "%4\$dx%5\$d").all { it in small })
            val bitrate = text("profiles_bitrate_sub")
            assertTrue("$folder: $bitrate", "%1\$d" in bitrate && "%2\$d" in bitrate && "400" !in bitrate)
            val fps = text("profiles_fps_sub")
            assertTrue("$folder: $fps", "%1\$d" in fps && "%2\$d" in fps && "4" !in fps)
        }
    }
}
