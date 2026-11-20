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
 * The video of the stream is fixed (plan 32, 20/11/2026) and the same in a debug and in a release build: 6 fps, a 400 kbps ceiling, the 528x304 picture
 * ([REauSettings.fpsCeiling], [REauSettings.streamBitrateBps], [REauSettings.videoFrame]). What [REauSettings.sanitized] makes of any stored or set value, how the
 * store ignores (and does not write) the keys the choices used to live in, and how the fixed values reach the ride log, the live tuning and the video loop.
 * What the governor does with them is PaceGovernorTest's; the line that says them on the screen is ProfileDetailsTest's.
 */
class FixedVideoStoreTest {
    private val fixed = listOf(6, 400_000, "528x304")

    private fun mixOf(s: REauSettings) = listOf(s.fpsCeiling, s.streamBitrateBps, s.videoFrame)

    // ---- the defaults and sanitized

    @Test
    fun `a debug and a release build both start at 6 fps, 400 kbps and 528x304`() {
        for (debugBuild in listOf(true, false)) {
            val s = REauSettings().sanitized(debugBuild = debugBuild)
            assertEquals("debugBuild=$debugBuild", fixed, mixOf(s))
            assertEquals(DashConfig.VIDEO_FRAME, s.frameSize)
            assertEquals(BitrateMode.CBR, s.bitrateMode)
            assertEquals(DashConfig.PROFILE_KEYFRAME_SEC, s.keyframeSec)
            assertEquals(400_000, s.effectiveStreamBitrateBps)
        }
        assertEquals("the settings' own defaults are the fixed ones, so no build differs", fixed, mixOf(REauSettings()))
    }

    @Test
    fun `any pace, ceiling and picture, set in code, is brought to the fixed ones in both builds, and nothing else moves`() {
        val base = REauSettings().sanitized(true)
        for (debugBuild in listOf(true, false)) {
            for (fps in listOf(0, 2, 4, 5, 7, 8, 10, 12, 16, 30)) for (bps in listOf(0, 200_000, 450_000, 600_000, 1_000_000, 1_024_000)) {
                for (picture in listOf("", "368x208", "448x256", "512x288", "526x300", "528x304", "abc")) {
                    val s = REauSettings(fpsCeiling = fps, streamBitrateBps = bps, videoFrame = picture).sanitized(debugBuild)
                    assertEquals("$fps fps $bps bps '$picture' debugBuild=$debugBuild", fixed, mixOf(s))
                    assertEquals(DashConfig.VIDEO_FRAME, s.frameSize)
                }
            }
            assertEquals(REauSettings().sanitized(debugBuild), REauSettings(fpsCeiling = 12, streamBitrateBps = 800_000).sanitized(debugBuild))
        }
        assertEquals(base.density, REauSettings(fpsCeiling = 12, density = base.density).sanitized(true).density)
    }

    // ---- the store

    private fun stored(build: (android.content.SharedPreferences.Editor) -> Unit) = KeystoreBackedPrefsTest.FakePrefs().also { build(it.edit()) }

    @Test
    fun `a fresh phone reads the fixed video`() {
        val loaded = SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load()
        assertEquals(REauSettings().sanitized(), loaded)
        assertEquals(fixed, mixOf(loaded))
    }

    @Test
    fun `every key the choices used to live in is ignored, as an old value`() {
        val prefs = stored {
            it.putInt("video_fps", 12).putInt("video_cap_bps", 900_000).putString("video_frame", "448x256").putString("video_profile", "12")
                .putInt("video_pace_fps", 8).putBoolean("experiment_fps63", true).putInt("fps_ceiling", 16).putInt("stream_bitrate", 1_024_000)
                .putBoolean("reserve_cap", false).apply()
        }
        assertEquals(fixed, mixOf(SettingsStore(prefs).load()))
        for (profile in listOf("4", "6", "8", "8m", "12", "", "twelve")) {
            assertEquals("profile '$profile'", fixed, mixOf(SettingsStore(stored { it.putString("video_profile", profile).apply() }).load()))
        }
        for (pace in listOf(0, 4, 6, 8, 12)) {
            assertEquals("pace $pace", fixed, mixOf(SettingsStore(stored { it.putInt("video_pace_fps", pace).apply() }).load()))
        }
        assertEquals(fixed, mixOf(SettingsStore(stored { it.putBoolean("experiment_fps63", false).apply() }).load()))
    }

    @Test
    fun `none of those keys is written any more, and the old ones are left in the file as they were`() {
        val prefs = stored { it.putInt("video_fps", 8).putInt("video_cap_bps", 800_000).putInt("fps_ceiling", 12).apply() }
        SettingsStore(prefs).save(SettingsStore(prefs).load().copy(density = 200))
        assertEquals("not read, not rewritten, not erased", 8, prefs.getInt("video_fps", 0))
        assertEquals(800_000, prefs.getInt("video_cap_bps", 0))
        assertEquals(12, prefs.getInt("fps_ceiling", 0))
        val fresh = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(fresh).save(REauSettings(fpsCeiling = 8, streamBitrateBps = 700_000))
        for (key in listOf("video_fps", "video_cap_bps", "video_frame", "video_profile", "video_pace_fps", "experiment_fps63", "fps_ceiling", "stream_bitrate", "reserve_cap")) {
            assertFalse("$key is written", fresh.contains(key))
        }
        assertEquals(fixed, mixOf(SettingsStore(fresh).load()))
        assertEquals(200, SettingsStore(prefs).load().density)
    }

    // ---- the guard, the ride log, the live tuning, the video loop

    @Test
    fun `none of it is an experimental switch, so the start guard has nothing to say about it`() {
        assertTrue(REauSettings::class.java.declaredFields.none { it.name == "reserveCapOn" || it.name == "experimentFpsSixThree" || it.name == "videoProfileId" })
        assertEquals(emptyList<String>(), ExperimentsGuard.experimentsOn(REauSettings()))
    }

    @Test
    fun `the ride log says the fixed values in the settings line, and a change of the picture, as the fall back makes it, is told`() {
        val line = SettingsInUse.line(REauSettings().sanitized(true))
        assertTrue(line, "fpsCeiling=6" in line && "streamBitrateBps=400000" in line && "videoFrame=528x304" in line)
        assertFalse(line, "reserveCapOn" in line)
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        assertEquals(listOf("videoFrame: 528x304 -> 526x300"), REauSettings().changesTo(REauSettings(videoFrame = "526x300")))
    }

    @Test
    fun `a stored change of pace or ceiling changes nothing, so it starts no stream, and the experiment of the lower bitrate still does`() {
        val base = REauSettings().sanitized(true)
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(base, REauSettings(fpsCeiling = 8, streamBitrateBps = 900_000).sanitized(true)))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, base.copy(experimentAdaptiveBitrate = true)))
    }

    @Test
    fun `it reaches the video loop as 6 fps, 400 kbps and 528x304 in both builds`() {
        val loop = VideoLoop.Settings()
        for (debugBuild in listOf(true, false)) {
            val s = loop.withRider(REauSettings(fpsCeiling = 8, streamBitrateBps = 900_000).sanitized(debugBuild))
            assertEquals("debugBuild=$debugBuild", listOf(6, 400_000, DashConfig.VIDEO_FRAME, 6), listOf(s.ceilingFps, s.bitrateBps, s.frame, s.lightScreenFps))
        }
    }

    @Test
    fun `the loop hands the fixed values to the governor it builds, and builds the encoder with the governor's rate`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val start = loop.substringAfter("val g = StreamGovernor(").substringBefore("val rec = recordInto(")
        assertTrue(start, start.contains("lightScreenFps = settings.lightScreenFps"))
        assertFalse(start, start.contains("manual") || start.contains("reserve") || start.contains("reducedBitrateBps"))
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec)"))
        assertTrue(loop.contains("frame = rider.frameSize,"))
        assertFalse("the reserve has no ceiling any more", loop.contains("ReserveCap") || loop.contains("lowFuel"))
    }
}
