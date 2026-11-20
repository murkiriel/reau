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
 * The profile of the stream ([REauSettings.videoProfileId], plan 32): the table of profiles ([DashConfig.STREAM_PROFILES]), what [REauSettings.sanitized] makes of it in a debug build
 * and in a release, how the store keeps it by id and reads the pace and the experiment it replaced, and how it reaches the ride log, the live tuning, the video loop and the settings screen.
 * What the governor does with it is PaceGovernorTest's.
 */
class VideoPaceTest {
    private fun at(id: String) = REauSettings(videoProfileId = id)

    // ---- the table

    @Test
    fun `the table is 4 fps at 400 kbps, 6 at 400, 8 at 600 twice and 12 at 800, and B is the exact half of both`() {
        assertEquals(
            listOf(
                listOf("4", 4, 400_000, 2, 200_000), listOf("6", 6, 400_000, 3, 200_000), listOf("8", 8, 600_000, 4, 300_000),
                listOf("8m", 8, 600_000, 4, 300_000), listOf("12", 12, 800_000, 6, 400_000),
            ),
            DashConfig.STREAM_PROFILES.map { listOf(it.id, it.fullFps, it.fullBps, it.reducedFps, it.reducedBps) },
        )
        assertEquals(listOf("4", "6", "8", "8m", "12"), DashConfig.PROFILE_IDS)
        assertEquals("the ids are never repeated", DashConfig.PROFILE_IDS.size, DashConfig.PROFILE_IDS.toSet().size)
        assertTrue("800 kbps is under the ceiling of every bitrate", DashConfig.STREAM_PROFILES.all { it.fullBps <= DashConfig.MAX_STREAM_BITRATE_BPS })
        assertEquals("the first is the default and the release's", DashConfig.DEFAULT_PROFILE_ID, DashConfig.STREAM_PROFILES.first().id)
        assertEquals(DashConfig.DEFAULT_STREAM_FPS, DashConfig.STREAM_PROFILES.first().fullFps)
        assertEquals(listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS), DashConfig.STREAM_PROFILES.first().let { listOf(it.fullFps, it.fullBps) })
    }

    @Test
    fun `a pace or an id the table does not have reads as the default, and a pace with two profiles reads as the first`() {
        assertEquals(DashConfig.STREAM_PROFILES.first(), DashConfig.streamProfile(5))
        assertEquals(DashConfig.STREAM_PROFILES.first(), DashConfig.streamProfile(0))
        assertEquals(DashConfig.StreamProfile("8", 8, 600_000), DashConfig.streamProfile(8))
        assertEquals(DashConfig.StreamProfile("12", 12, 800_000, DashConfig.REDUCED_FRAME), DashConfig.streamProfile(12))
        assertEquals(DashConfig.StreamProfile("8m", 8, 600_000, DashConfig.MEDIUM_FRAME), DashConfig.profileById("8m"))
        for (bad in listOf("", "5", "8M", "m8", "12 ", "x")) assertEquals("id '$bad'", DashConfig.STREAM_PROFILES.first(), DashConfig.profileById(bad))
        assertEquals(DashConfig.STREAM_PROFILES.first(), DashConfig.profileById(null))
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
        assertEquals("4", REauSettings().videoProfileId)
        assertEquals(4, VideoLoop.Settings().let { DashConfig.streamProfile(it.ceilingFps.coerceAtMost(4)).fullFps })
        for (debug in listOf(true, false)) {
            val s = REauSettings().sanitized(debug)
            assertEquals("debug=$debug", listOf(4, 2, 400_000, 200_000), listOf(s.fpsCeiling, s.profileReducedFps, s.streamBitrateBps, s.profileReducedBps))
        }
    }

    @Test
    fun `in a debug build every profile of the table is taken, with the ceiling, the bitrate and B from the table`() {
        for (p in DashConfig.STREAM_PROFILES) {
            val s = at(p.id).sanitized(debugBuild = true)
            assertEquals(p.id, s.videoProfileId)
            assertEquals(p, s.streamProfile)
            assertEquals(listOf(p.fullFps, p.fullBps, p.reducedFps, p.reducedBps), listOf(s.fpsCeiling, s.streamBitrateBps, s.profileReducedFps, s.profileReducedBps))
            assertEquals(p.fullBps, s.effectiveStreamBitrateBps)
            assertEquals(BitrateMode.CBR, s.bitrateMode)
            assertEquals(DashConfig.PROFILE_KEYFRAME_SEC, s.keyframeSec)
            assertEquals("nothing else moves", REauSettings().sanitized(true), s.copy(videoProfileId = "4", fpsCeiling = 4, streamBitrateBps = 400_000))
        }
    }

    @Test
    fun `in a debug build any other id reads as 4`() {
        for (bad in listOf("", "0", "2", "5", "10", "16", "30", "-6", "8M", "m8", "8 ", "twelve")) {
            val s = at(bad).sanitized(debugBuild = true)
            assertEquals("profile '$bad'", listOf("4", 4, 400_000), listOf(s.videoProfileId, s.fpsCeiling, s.streamBitrateBps))
        }
    }

    @Test
    fun `in a release build it is always 4, whatever was stored`() {
        for (id in DashConfig.PROFILE_IDS) {
            val s = at(id).sanitized(debugBuild = false)
            assertEquals("profile $id", listOf("4", 4, 2, 400_000, 200_000), listOf(s.videoProfileId, s.fpsCeiling, s.profileReducedFps, s.streamBitrateBps, s.profileReducedBps))
            assertEquals(REauSettings().sanitized(false), s)
        }
        assertEquals("a ceiling of 8 stored by a debug build does not survive in a release", 4, at("4").copy(fpsCeiling = 8).sanitized(false).fpsCeiling)
    }

    // ---- the store

    @Test
    fun `it is stored and read back on its own`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertEquals("4", store.load().videoProfileId)
        store.save(at("8m"))
        // the store sanitizes with the build it runs in: "8m" in a debug build, "4" in a release
        assertEquals(if (com.reau.app.BuildConfig.DEBUG) "8m" else "4", store.load().videoProfileId)
        assertEquals(at("8m").sanitized(), store.load())
        for (id in DashConfig.PROFILE_IDS) {
            store.save(at(id))
            assertEquals(at(id).sanitized(), store.load())
        }
        store.save(REauSettings())
        assertEquals("4", store.load().videoProfileId)
        assertEquals(4, store.load().fpsCeiling)
    }

    @Test
    fun `a phone that stored the experiment on reads 6, off or never stored reads 4`() {
        val on = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", true).apply() }
        assertEquals(at("6").sanitized(), SettingsStore(on).load())
        val off = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", false).apply() }
        assertEquals("4", SettingsStore(off).load().videoProfileId)
        val none = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("density", 200).apply() }
        assertEquals("4", SettingsStore(none).load().videoProfileId)
    }

    @Test
    fun `a phone that stored the pace alone reads the first profile of that pace, so 8 is the full frame`() {
        for ((pace, id) in listOf(4 to "4", 6 to "6", 8 to "8", 12 to "12", 5 to "4", 0 to "4", 30 to "4")) {
            val prefs = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("video_pace_fps", pace).apply() }
            assertEquals("pace $pace", at(id).sanitized(), SettingsStore(prefs).load())
        }
        val both = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putInt("video_pace_fps", 8).putBoolean("experiment_fps63", true).apply() }
        assertEquals("the pace is newer than the experiment", at("8").sanitized(), SettingsStore(both).load())
    }

    @Test
    fun `once the profile is saved the older keys are not read again`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs().also { it.edit().putBoolean("experiment_fps63", true).putInt("video_pace_fps", 12).apply() }
        val store = SettingsStore(prefs)
        store.save(store.load().copy(videoProfileId = "4"))
        assertEquals("the rider went back to 4 and the old keys do not undo it", "4", SettingsStore(prefs).load().videoProfileId)
        store.save(at("8m"))
        assertEquals(at("8m").sanitized(), SettingsStore(prefs).load())
    }

    @Test
    fun `the profile is stored by its id, in a key of its own`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(at("8m"))
        assertEquals("8m", prefs.getString("video_profile", null))
    }

    // ---- the guard, the ride log, the live tuning, the video loop

    @Test
    fun `it is no experimental switch, so the start guard has nothing to say about it`() {
        assertTrue(REauSettings::class.java.declaredFields.none { it.name == "experimentFpsSixThree" })
        assertEquals(emptyList<String>(), ExperimentsGuard.experimentsOn(at("8m")))
        assertEquals(at("8m"), ExperimentsGuard.allOff(at("8m")))
    }

    @Test
    fun `the ride log says it in the settings line, and a change of it is told`() {
        assertTrue("videoProfileId=4" in SettingsInUse.line(REauSettings()))
        assertTrue("videoProfileId=8m" in SettingsInUse.line(at("8m")))
        assertTrue("the ceiling it sets is in the line too", "fpsCeiling=8" in SettingsInUse.line(at("8m").sanitized(true)))
        assertNull(SettingsInUse.experimentsLine(at("8m")))
        assertEquals(listOf("videoProfileId: 4 -> 8m"), REauSettings().changesTo(at("8m")))
    }

    @Test
    fun `a change of it starts the stream again, the one to or from 12 fps and between the two 8 fps too`() {
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(REauSettings().sanitized(true), at("8").sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at("8").sanitized(true), at("12").sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at("12").sanitized(true), at("6").sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at("6").sanitized(true), at("8").sanitized(true)))
        assertEquals("same pace and bitrate, another picture", setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(at("8").sanitized(true), at("8m").sanitized(true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(at("8").sanitized(true), at("8").sanitized(true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(at("8m").sanitized(true), at("8m").sanitized(true)))
    }

    @Test
    fun `it reaches the video loop with the pace, A's bitrate and B's, and in a release it is the usual one`() {
        val loop = VideoLoop.Settings()
        val off = loop.withRider(REauSettings().sanitized(true))
        assertEquals(listOf(4, 400_000, 200_000), listOf(off.ceilingFps, off.bitrateBps, off.reducedBitrateBps))
        val s = loop.withRider(at("8").sanitized(true))
        assertEquals(listOf(8, 600_000, 300_000, 6), listOf(s.ceilingFps, s.bitrateBps, s.reducedBitrateBps, s.lightScreenFps))
        assertEquals(false, s.colorMatrix)
        val release = loop.withRider(at("8").sanitized(false))
        assertEquals(listOf(4, 400_000, 200_000), listOf(release.ceilingFps, release.bitrateBps, release.reducedBitrateBps))
        val medium = loop.withRider(at("8m").sanitized(true))
        assertEquals(listOf(8, 600_000, 300_000), listOf(medium.ceilingFps, medium.bitrateBps, medium.reducedBitrateBps))
        val small = loop.withRider(at("12").sanitized(true))
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
    fun `the profile rows pick the profile by id in a debug build and show it locked in a release, with the bitrates of the table`() {
        val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        assertTrue("the chooser is a debug build's", rows.contains("if (com.reau.app.BuildConfig.DEBUG) {"))
        assertTrue(rows.contains("DashConfig.STREAM_PROFILES.map { p ->"))
        assertTrue(rows.contains("name to \"pace_\${p.id}\""))
        assertTrue(rows.contains("DashConfig.PROFILE_IDS.indexOf(s.streamProfile.id), { change(s.copy(videoProfileId = DashConfig.PROFILE_IDS[it])) }"))
        assertTrue("only a profile with a smaller picture says its size", rows.contains("val name = if (p.frame == DashConfig.FULL_FRAME) {\n                    stringResource(R.string.pace_option, p.fullFps)\n                } else {"))
        assertTrue("a profile with a smaller picture says its size", rows.contains("stringResource(R.string.pace_option_picture, p.fullFps, p.frame.width, p.frame.height)"))
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
            assertEquals("%1\$d fps, %2\$dx%3\$d", text("pace_option_picture"))
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
