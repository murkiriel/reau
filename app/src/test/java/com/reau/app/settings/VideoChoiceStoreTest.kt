package com.reau.app.settings

import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.DashConfig.FrameSize
import com.reau.app.dash.VideoChoice
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
 * The three choices of the stream ([REauSettings.fpsCeiling], [REauSettings.streamBitrateBps], [REauSettings.videoFrame], plan 32): what [REauSettings.sanitized] makes of them in a debug
 * build and in a release, how the store keeps them and reads the profile id, the pace and the experiment they replaced, and how they reach the ride log, the live tuning, the video loop and the
 * settings screen. The values themselves are VideoChoiceTest's; what the governor does with them is PaceGovernorTest's.
 */
class VideoChoiceStoreTest {
    private val full = FrameSize(528, 304)
    private val medium = FrameSize(448, 256)

    private fun mix(fps: Int, kbps: Int, frame: FrameSize) = REauSettings(fpsCeiling = fps, streamBitrateBps = kbps * 1000, videoFrame = frame.id)

    // ---- the defaults and sanitized

    @Test
    fun `the debug build starts at 6 fps, 400 kbps and 528x304`() {
        val s = REauSettings().sanitized(debugBuild = true)
        assertEquals(listOf(6, 400_000, "528x304"), listOf(s.fpsCeiling, s.streamBitrateBps, s.videoFrame))
        assertEquals(full, s.frameSize)
        assertEquals(BitrateMode.CBR, s.bitrateMode)
        assertEquals(DashConfig.PROFILE_KEYFRAME_SEC, s.keyframeSec)
    }

    @Test
    fun `in a debug build every mix of the three is kept as chosen, and nothing else moves`() {
        for (fps in VideoChoice.FPS_OPTIONS) for (kbps in VideoChoice.CAP_OPTIONS_KBPS) for (f in VideoChoice.FRAMES) {
            val s = mix(fps, kbps, f).sanitized(debugBuild = true)
            assertEquals(listOf(fps, kbps * 1000, f.id), listOf(s.fpsCeiling, s.streamBitrateBps, s.videoFrame))
            assertEquals(f, s.frameSize)
            assertEquals(kbps * 1000, s.effectiveStreamBitrateBps)
            assertEquals("nothing else moves", REauSettings().sanitized(true), s.copy(fpsCeiling = 6, streamBitrateBps = 400_000, videoFrame = "528x304"))
        }
    }

    @Test
    fun `in a debug build a value outside the offer is brought into it`() {
        assertEquals(4, mix(0, 400, full).sanitized(true).fpsCeiling)
        assertEquals(4, mix(2, 400, full).sanitized(true).fpsCeiling)
        assertEquals(6, mix(7, 400, full).sanitized(true).fpsCeiling)
        assertEquals(12, mix(30, 400, full).sanitized(true).fpsCeiling)
        assertEquals(400_000, REauSettings(streamBitrateBps = 200_000).sanitized(true).streamBitrateBps)
        assertEquals(1_000_000, REauSettings(streamBitrateBps = 1_024_000).sanitized(true).streamBitrateBps)
        assertEquals(500_000, REauSettings(streamBitrateBps = 450_000).sanitized(true).streamBitrateBps)
        for (bad in listOf("", "368x208", "526x300", "abc", "528x305")) {
            val s = REauSettings(videoFrame = bad).sanitized(true)
            assertEquals("picture '$bad'", full, s.frameSize)
            assertEquals(VideoChoice.DEFAULT_FRAME.id, s.videoFrame)
        }
    }

    @Test
    fun `in a release build it is always 4 fps, 400 kbps and 526x300, whatever was stored`() {
        val expected = REauSettings().sanitized(debugBuild = false)
        assertEquals(listOf(4, 400_000, "526x300"), listOf(expected.fpsCeiling, expected.streamBitrateBps, expected.videoFrame))
        assertEquals(DashConfig.FULL_FRAME, expected.frameSize)
        for (fps in VideoChoice.FPS_OPTIONS) for (f in VideoChoice.FRAMES) for (kbps in listOf(400, 600, 1000)) {
            assertEquals("$fps $kbps ${f.id}", expected, mix(fps, kbps, f).sanitized(debugBuild = false))
        }
        assertEquals(400_000, expected.effectiveStreamBitrateBps)
    }

    @Test
    fun `only a debug build's stream is manual, a release build keeps the profiles A and B`() {
        assertEquals(com.reau.app.BuildConfig.DEBUG, REauSettings().manualVideo)
        assertFalse("a stream made without the rider's settings is not manual", VideoLoop.Settings().manual)
        val settings = File("src/main/java/com/reau/app/settings/REauSettings.kt").readText()
        assertTrue(settings.contains("val manualVideo: Boolean get() = com.reau.app.BuildConfig.DEBUG"))
        assertEquals(com.reau.app.BuildConfig.DEBUG, VideoLoop.Settings().withRider(REauSettings().sanitized(true)).manual)
    }

    // ---- the guide and the warning on the settings

    @Test
    fun `the settings carry the guide and the warning of the mix they hold`() {
        assertEquals(400, mix(6, 400, full).recommendedKbps)
        assertEquals(600, mix(8, 400, full).recommendedKbps)
        assertEquals(800, mix(12, 400, full).recommendedKbps)
        assertEquals(400, mix(8, 400, medium).recommendedKbps)
        assertTrue(mix(8, 600, full).heavyLoad)
        assertTrue(mix(12, 800, full).heavyLoad)
        assertFalse(mix(6, 400, full).heavyLoad)
        assertFalse(mix(8, 600, medium).heavyLoad)
    }

    // ---- the switch of the reserve's cap

    @Test
    fun `the cap in the reserve is on by default, and a debug build may turn it off`() {
        assertTrue(REauSettings().reserveCapOn)
        assertTrue(REauSettings().sanitized(true).reserveCapOn)
        assertFalse(REauSettings(reserveCapOn = false).sanitized(debugBuild = true).reserveCapOn)
        assertEquals("nothing else moves", REauSettings().sanitized(true), REauSettings(reserveCapOn = false).sanitized(true).copy(reserveCapOn = true))
    }

    @Test
    fun `a release build always has the cap on, whatever a debug build left stored`() {
        assertTrue(REauSettings(reserveCapOn = false).sanitized(debugBuild = false).reserveCapOn)
        assertEquals(REauSettings().sanitized(false), REauSettings(reserveCapOn = false).sanitized(debugBuild = false))
    }

    @Test
    fun `the switch is stored in a key of its own, on when never stored`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        assertTrue(store.load().reserveCapOn)
        store.save(REauSettings(reserveCapOn = false))
        assertEquals(false, prefs.getBoolean("reserve_cap", true))
        assertEquals(!com.reau.app.BuildConfig.DEBUG, store.load().reserveCapOn)
        store.save(REauSettings(reserveCapOn = true))
        assertTrue(store.load().reserveCapOn)
    }

    // ---- the store

    @Test
    fun `the three choices are stored in keys of their own and read back`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        val s = mix(10, 800, FrameSize(480, 272))
        store.save(s)
        assertEquals(10, prefs.getInt("video_fps", 0))
        assertEquals(800_000, prefs.getInt("video_cap_bps", 0))
        assertEquals("480x272", prefs.getString("video_frame", null))
        // the store sanitizes with the build it runs in
        assertEquals(s.sanitized(), store.load())
        for (fps in VideoChoice.FPS_OPTIONS) for (f in VideoChoice.FRAMES) {
            val m = mix(fps, 500, f)
            store.save(m)
            assertEquals(m.sanitized(), store.load())
        }
    }

    @Test
    fun `a fresh phone reads the debug defaults, and a release reads its fixed values`() {
        val loaded = SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load()
        assertEquals(REauSettings().sanitized(), loaded)
        assertEquals(if (com.reau.app.BuildConfig.DEBUG) listOf(6, 400_000, "528x304") else listOf(4, 400_000, "526x300"), listOf(loaded.fpsCeiling, loaded.streamBitrateBps, loaded.videoFrame))
    }

    // ---- the migration

    private fun stored(build: (android.content.SharedPreferences.Editor) -> Unit) = KeystoreBackedPrefsTest.FakePrefs().also { build(it.edit()) }

    private fun mixOf(prefs: android.content.SharedPreferences): List<Any> =
        SettingsStore(prefs).load().let { listOf(it.fpsCeiling, it.streamBitrateBps / 1000, it.videoFrame) }

    @Test
    fun `each profile id of the first build becomes the three choices`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        val wanted = mapOf(
            "4" to listOf(4, 400, "528x304"), "6" to listOf(6, 400, "528x304"), "8" to listOf(8, 600, "528x304"),
            "8m" to listOf(8, 600, "448x256"), "12" to listOf(12, 800, "448x256"),
        )
        for ((id, three) in wanted) {
            val prefs = stored { it.putString("video_profile", id).apply() }
            assertEquals("profile $id", three, mixOf(prefs))
        }
    }

    @Test
    fun `a profile id the table never had reads as 4 fps`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        for (bad in listOf("", "5", "8M", "twelve")) {
            val prefs = stored { it.putString("video_profile", bad).apply() }
            assertEquals("id '$bad'", listOf(4, 400, "528x304"), mixOf(prefs))
        }
    }

    @Test
    fun `a phone that stored the experiment on reads 6, off or never stored reads the defaults`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        assertEquals(listOf(6, 400, "528x304"), mixOf(stored { it.putBoolean("experiment_fps63", true).apply() }))
        assertEquals(listOf(6, 400, "528x304"), mixOf(stored { it.putBoolean("experiment_fps63", false).apply() }))
        assertEquals(listOf(6, 400, "528x304"), mixOf(stored { it.putInt("density", 200).apply() }))
    }

    @Test
    fun `a phone that stored the pace alone reads the first profile of that pace`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        for ((pace, three) in listOf(
            4 to listOf(4, 400, "528x304"), 6 to listOf(6, 400, "528x304"), 8 to listOf(8, 600, "528x304"), 12 to listOf(12, 800, "448x256"),
            5 to listOf(4, 400, "528x304"), 0 to listOf(4, 400, "528x304"),
        )) {
            assertEquals("pace $pace", three, mixOf(stored { it.putInt("video_pace_fps", pace).apply() }))
        }
        assertEquals("the pace is newer than the experiment", listOf(8, 600, "528x304"), mixOf(stored { it.putInt("video_pace_fps", 8).putBoolean("experiment_fps63", true).apply() }))
        assertEquals("the profile id is newer than the pace", listOf(6, 400, "528x304"), mixOf(stored { it.putString("video_profile", "6").putInt("video_pace_fps", 8).apply() }))
    }

    @Test
    fun `once the new keys are saved the older ones are not read again`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        val prefs = stored { it.putString("video_profile", "12").putBoolean("experiment_fps63", true).putInt("video_pace_fps", 12).apply() }
        val store = SettingsStore(prefs)
        assertEquals(listOf(12, 800, "448x256"), mixOf(prefs))
        store.save(store.load().copy(fpsCeiling = 10, streamBitrateBps = 500_000, videoFrame = "512x288"))
        assertEquals("the rider's choice and the old keys do not undo it", listOf(10, 500, "512x288"), mixOf(prefs))
        store.save(mix(4, 400, full))
        assertEquals(listOf(4, 400, "528x304"), mixOf(prefs))
    }

    @Test
    fun `the old keys of the pace and the ceiling are neither read nor written any more`() {
        if (!com.reau.app.BuildConfig.DEBUG) return
        val prefs = stored { it.putInt("fps_ceiling", 8).putInt("stream_bitrate", 900_000).apply() }
        assertEquals("only the new keys and the migrated ones count", listOf(6, 400, "528x304"), mixOf(prefs))
        SettingsStore(prefs).save(mix(10, 700, full))
        assertEquals(8, prefs.getInt("fps_ceiling", 0))
        assertEquals(900_000, prefs.getInt("stream_bitrate", 0))
        val store = File("src/main/java/com/reau/app/settings/SettingsStore.kt").readText()
        assertFalse(store.contains("KEY_FPS,") || store.contains("KEY_BITRATE,"))
    }

    // ---- the guard, the ride log, the live tuning, the video loop

    @Test
    fun `none of it is an experimental switch, so the start guard has nothing to say about it`() {
        assertTrue(REauSettings::class.java.declaredFields.none { it.name == "experimentFpsSixThree" || it.name == "videoProfileId" })
        assertEquals(emptyList<String>(), ExperimentsGuard.experimentsOn(mix(8, 600, medium)))
        assertEquals(mix(8, 600, medium), ExperimentsGuard.allOff(mix(8, 600, medium)))
    }

    @Test
    fun `the ride log says the three choices in the settings line, and a change of them is told`() {
        val line = SettingsInUse.line(REauSettings().sanitized(true))
        assertTrue(line, "fpsCeiling=6" in line && "streamBitrateBps=400000" in line && "videoFrame=528x304" in line)
        assertNull(SettingsInUse.experimentsLine(mix(8, 600, medium)))
        assertEquals(listOf("videoFrame: 528x304 -> 448x256"), REauSettings().changesTo(REauSettings(videoFrame = "448x256")))
    }

    @Test
    fun `a change of the pace, of the ceiling or of the picture starts the stream again, and nothing else does`() {
        val base = mix(6, 400, full).sanitized(true)
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, mix(8, 400, full).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, mix(6, 500, full).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, mix(6, 400, medium).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, mix(12, 1000, FrameSize(496, 288)).sanitized(true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(base, mix(6, 400, full).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, base.copy(reserveCapOn = false)))
    }

    @Test
    fun `it reaches the video loop as chosen in a debug build, and in a release as the usual 4 fps, 400 and 526x300`() {
        val loop = VideoLoop.Settings()
        val s = loop.withRider(mix(10, 700, FrameSize(464, 272)).sanitized(true))
        assertEquals(listOf(10, 700_000, FrameSize(464, 272), 6), listOf(s.ceilingFps, s.bitrateBps, s.frame, s.lightScreenFps))
        val release = loop.withRider(mix(8, 900, medium).sanitized(false))
        assertEquals(listOf(4, 400_000, 200_000, DashConfig.FULL_FRAME), listOf(release.ceilingFps, release.bitrateBps, release.reducedBitrateBps, release.frame))
    }

    @Test
    fun `the loop hands the choices to the governor it builds, and builds the encoder with the governor's rate`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val start = loop.substringAfter("val g = StreamGovernor(").substringBefore("val rec = recordInto(")
        assertTrue(start, start.contains("reducedBitrateBps = settings.reducedBitrateBps"))
        assertTrue(start, start.contains("manual = settings.manual,"))
        assertTrue(loop.contains("reserveCapOn = settings.reserveCap,"))
        assertTrue(loop.contains("reserveCap = rider.reserveCapOn,"))
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec)"))
        assertTrue(loop.contains("manual = rider.manualVideo,"))
        assertTrue(loop.contains("frame = rider.frameSize,"))
    }

    // ---- the settings screen

    @Test
    fun `the video rows have the pace, the ceiling slider with its guide and the picture in a debug build, then the warning and the reserve's switch`() {
        val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        val debug = rows.substringBefore("} else {\n        LockedRow(")
        assertTrue("the chooser is a debug build's", rows.contains("if (com.reau.app.BuildConfig.DEBUG) {"))
        assertTrue(debug.contains("VideoChoice.FPS_OPTIONS.map { stringResource(R.string.pace_option, it) to \"pace_\$it\" }"))
        assertTrue(debug.contains("{ change(s.copy(fpsCeiling = VideoChoice.FPS_OPTIONS[it])) }"))
        assertTrue(debug.contains("SliderRow("))
        assertTrue(debug.contains("VideoChoice.CAP_MIN_KBPS, VideoChoice.CAP_MAX_KBPS, VideoChoice.CAP_STEP_KBPS"))
        assertTrue(debug.contains("{ change(s.copy(streamBitrateBps = it * KBPS)) }, \"bitrate\""))
        assertTrue("the guide is told next to the slider", debug.contains("stringResource(R.string.bitrate_cap_sub, s.recommendedKbps)"))
        assertTrue(debug.contains("VideoChoice.FRAMES.map { stringResource(R.string.frame_option, it.width, it.height) to \"frame_\${it.id}\" }"))
        assertTrue(debug.contains("{ change(s.copy(videoFrame = VideoChoice.FRAMES[it].id)) }"))
        assertTrue("the warning is only shown for a heavy mix", debug.contains("if (s.heavyLoad) HeavyLoadWarning(VideoChoice.loadMbPerSec(s.fpsCeiling, s.frameSize))"))
        assertTrue("order: pace, ceiling, picture, warning, switch", listOf("tag = \"fps\"", "\"bitrate\"", "tag = \"frame\"", "HeavyLoadWarning(", "\"reserve_cap\"").map { debug.indexOf(it) }.let { it == it.sorted() && it.all { i -> i >= 0 } })
        assertTrue(debug.contains("s.reserveCapOn, { change(s.copy(reserveCapOn = it)) }, \"reserve_cap\","))
    }

    @Test
    fun `a release build only shows the fixed values locked, with A and B as they were`() {
        val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        val release = rows.substringAfter("} else {\n        LockedRow(")
        assertFalse("no slider, no picture chooser, no switch", release.contains("SliderRow") || release.contains("\"frame\"") || release.contains("reserve_cap"))
        assertTrue(release.contains("R.string.profiles_fps_sub, s.fpsCeiling, s.fpsCeiling / 2"))
        assertTrue(release.contains("R.string.profiles_info, s.fpsCeiling, s.streamBitrateBps / KBPS, s.fpsCeiling / 2, s.streamBitrateBps / 2 / KBPS"))
        assertTrue(release.contains("R.string.profiles_bitrate_sub, s.streamBitrateBps / KBPS, s.streamBitrateBps / 2 / KBPS"))
    }

    @Test
    fun `the strings are in the three languages, with the numbers as arguments`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            fun text(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(xml)?.groupValues?.get(1) ?: error("no $name in $folder")
            assertEquals("%1\$d fps", text("pace_option"))
            assertTrue("$folder pace_sub", "%1\$d" in text("pace_sub"))
            assertTrue("$folder bitrate_cap_sub", "%1\$d" in text("bitrate_cap_sub") && "400" in text("bitrate_cap_sub") && "1000" in text("bitrate_cap_sub"))
            assertEquals("%1\$dx%2\$d", text("frame_option"))
            assertTrue("$folder frame_sub", "528x304" in text("frame_sub"))
            val warn = text("load_warning")
            assertTrue("$folder load_warning", "%1\$d" in warn && "%2\$d" in warn)
            assertTrue("$folder title", "%1\$d" in text("reserve_cap_title") && "%1\$d" in text("reserve_cap_sub"))
            assertFalse("$folder still has the strings of the profiles with a picture", xml.contains("pace_option_picture") || xml.contains("pace_small_sub"))
            assertFalse("$folder still has the experiment", xml.contains("exp_fps63"))
            for (name in listOf("bitrate_cap_title", "frame_title")) text(name)
        }
        val experiments = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        assertFalse("the 6/3 experiment's row is gone", experiments.contains("fps63") || experiments.contains("experimentFpsSixThree"))
    }
}
