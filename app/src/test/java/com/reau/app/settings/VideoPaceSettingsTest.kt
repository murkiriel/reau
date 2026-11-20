package com.reau.app.settings

import com.reau.app.dash.DashConfig
import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import com.reau.app.stream.VideoLoop
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video pace (plan 32, 20/11/2026): REau (A 6 fps, B 3) or Original (A 4, B 2), the rider's only choice about the video, in a debug and in a release
 * build alike. The default, what [REauSettings.sanitized] makes of it, how the store keeps it (an unknown or missing name is REau), how it reaches the
 * video loop and the live tuning, and the ride log. What the governor does with each pace is VideoPaceGovernorTest's; the row is VideoPaceChoiceTest's.
 */
class VideoPaceSettingsTest {
    private fun stored(build: (android.content.SharedPreferences.Editor) -> Unit) = KeystoreBackedPrefsTest.FakePrefs().also { build(it.edit()) }

    @Test
    fun `there are two paces, REau first, with the numbers the owner asked for`() {
        assertEquals(listOf(VideoPace.REAU, VideoPace.ORIGINAL), VideoPace.entries)
        assertEquals(listOf(6, 3), listOf(VideoPace.REAU.fullFps, VideoPace.REAU.reducedFps))
        assertEquals(listOf(4, 2), listOf(VideoPace.ORIGINAL.fullFps, VideoPace.ORIGINAL.reducedFps))
        for (pace in VideoPace.entries) {
            assertEquals("$pace: B is the exact half of A", pace.fullFps / 2, pace.reducedFps)
            assertEquals("$pace: the light screens are never above A", pace.fullFps, pace.lightScreenFps)
        }
        assertEquals("REau is DashConfig's profile pair", listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_REDUCED_FPS), listOf(6, 3))
    }

    @Test
    fun `the default is REau, in the settings and after sanitizing in both builds`() {
        assertEquals(VideoPace.REAU, REauSettings().videoPace)
        for (debugBuild in listOf(true, false)) {
            val s = REauSettings().sanitized(debugBuild)
            assertEquals(VideoPace.REAU, s.videoPace)
            assertEquals(6, s.fpsCeiling)
        }
    }

    @Test
    fun `the pace is kept in a debug and in a release build, and A's pace follows it`() {
        for (debugBuild in listOf(true, false)) for (pace in VideoPace.entries) {
            val s = REauSettings(videoPace = pace).sanitized(debugBuild)
            assertEquals("$pace debugBuild=$debugBuild", pace, s.videoPace)
            assertEquals(pace.fullFps, s.fpsCeiling)
            assertEquals("the rest of the stream is the same", listOf(400_000, "528x304", 1), listOf(s.streamBitrateBps, s.videoFrame, s.keyframeSec))
        }
        // a stale pace of A, from code or from an old file, never wins over the pace chosen
        assertEquals(4, REauSettings(videoPace = VideoPace.ORIGINAL, fpsCeiling = 12).sanitized(false).fpsCeiling)
        assertEquals(6, REauSettings(videoPace = VideoPace.REAU, fpsCeiling = 4).sanitized(true).fpsCeiling)
    }

    @Test
    fun `each pace is saved and read back as it was`() {
        for (pace in VideoPace.entries) {
            val prefs = KeystoreBackedPrefsTest.FakePrefs()
            SettingsStore(prefs).save(REauSettings(videoPace = pace))
            assertEquals(pace.name, prefs.getString("video_pace_mode", null))
            assertEquals(pace, SettingsStore(prefs).load().videoPace)
            assertEquals(pace.fullFps, SettingsStore(prefs).load().fpsCeiling)
        }
    }

    @Test
    fun `an unknown, empty or missing pace is REau`() {
        assertEquals(VideoPace.REAU, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().videoPace)
        for (name in listOf("", "original", "FAST", "6", "REAU ", "null", "ORIGINAL2")) {
            val loaded = SettingsStore(stored { it.putString("video_pace_mode", name).apply() }).load()
            assertEquals("'$name'", VideoPace.REAU, loaded.videoPace)
            assertEquals(6, loaded.fpsCeiling)
        }
        // the key the old pace choice lived in is another one and is still ignored
        assertEquals(VideoPace.REAU, SettingsStore(stored { it.putInt("video_pace_fps", 4).putString("video_pace", "ORIGINAL").apply() }).load().videoPace)
    }

    @Test
    fun `a pace saved with other settings leaves the other settings as they are`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(prefs)
        store.save(store.load().copy(density = 200, videoPace = VideoPace.ORIGINAL))
        val loaded = store.load()
        assertEquals(listOf(200, VideoPace.ORIGINAL), listOf(loaded.density, loaded.videoPace))
        store.save(loaded.copy(videoPace = VideoPace.REAU))
        assertEquals(listOf(200, VideoPace.REAU), store.load().let { listOf(it.density, it.videoPace) })
    }

    @Test
    fun `it reaches the video loop as the pace of A and the light screens' pace, with the same bitrate and picture`() {
        val loop = VideoLoop.Settings()
        for (pace in VideoPace.entries) for (debugBuild in listOf(true, false)) {
            val s = loop.withRider(REauSettings(videoPace = pace).sanitized(debugBuild))
            assertEquals("$pace debugBuild=$debugBuild", listOf(pace.fullFps, 400_000, DashConfig.VIDEO_FRAME, pace.fullFps), listOf(s.ceilingFps, s.bitrateBps, s.frame, s.lightScreenFps))
        }
        assertEquals(6, REauSettings.lightScreenFps())
        assertEquals(4, REauSettings.lightScreenFps(VideoPace.ORIGINAL))
        assertEquals(DashConfig.LIGHT_SCREEN_FPS, REauSettings.lightScreenFps(VideoPace.REAU))
    }

    @Test
    fun `it is a normal setting, not an experiment`() {
        assertTrue("no experiment is named after the pace", REauSettings::class.java.declaredFields.none { it.name.startsWith("experiment") && it.name.contains("VideoPace") })
    }

    @Test
    fun `the live tuning takes a change of pace as a new stream and nothing else`() {
        val base = REauSettings().sanitized(true)
        val original = base.copy(videoPace = VideoPace.ORIGINAL).sanitized(true)
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(base, original))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(original, base))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(original, original.copy()))
    }

    @Test
    fun `the running projection logs the new pace and starts the new stream with the settings it was given`() {
        val sides = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        val branch = sides.substringAfter("if (LiveTuning.Step.NEW_STREAM in steps) {").substringBefore("if (LiveTuning.Step.NIGHT_NOW in steps)")
        assertTrue(branch, branch.contains("before.videoPace != settings.videoPace"))
        assertTrue(branch, branch.contains("RideDiagnostics.log(\"stream\", settings.videoPace.logLine())"))
        assertTrue(branch, branch.contains("newStream(settings)"))
        val stream = sides.substringAfter("fun newStream(settings: REauSettings) {").substringBefore("fun followFallback")
        assertTrue(stream, stream.contains("videoSettings = videoSettings.withRider(settings)"))
        assertTrue("the same session: only the stream's loop is replaced", stream.contains("loops.replace { newLoop() }"))
        assertFalse(stream, stream.contains("reconfigure") || stream.contains("newAndroidAutoSession"))
    }

    @Test
    fun `the ride log's line says the pace, and the settings line carries it`() {
        assertEquals("video pace: REAU (A 6 fps / B 3 fps)", VideoPace.REAU.logLine())
        assertEquals("video pace: ORIGINAL (A 4 fps / B 2 fps)", VideoPace.ORIGINAL.logLine())
        assertTrue(SettingsInUse.line(REauSettings()).contains(" videoPace=REAU "))
        assertTrue(SettingsInUse.line(REauSettings(videoPace = VideoPace.ORIGINAL)).contains(" videoPace=ORIGINAL "))
        assertEquals(listOf("videoPace: REAU -> ORIGINAL"), REauSettings().changesTo(REauSettings(videoPace = VideoPace.ORIGINAL)))
    }
}
