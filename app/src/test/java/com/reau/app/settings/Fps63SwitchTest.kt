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
 * The debug build's 6/3 fps experiment ([REauSettings.experimentFpsSixThree]): off by default; on, and only in a debug build, the full profile is 6 fps and the reduced one 3 with the
 * bitrates as they are; a release build has it off whatever was stored. It is stored on its own, known by the start guard, said in the ride log (the line the analysis of
 * the rides reads to tell the 6/3 rides from the 4/2 ones), handed to the video loop and from it to the governor, and shown in the settings in the three languages.
 * What the governor does with it is Fps63GovernorTest's.
 */
class Fps63SwitchTest {
    private val on = REauSettings(experimentFpsSixThree = true)

    @Test
    fun `it is off by default, and then the profiles are 4 and 2 fps at 400 and 200 kbps as always`() {
        assertFalse(REauSettings().experimentFpsSixThree)
        assertFalse(VideoLoop.Settings().exactHalfReduced)
        for (debug in listOf(true, false)) {
            val s = REauSettings().sanitized(debug)
            assertEquals("debug=$debug", listOf(4, 2, 400_000), listOf(s.fpsCeiling, s.profileReducedFps, s.streamBitrateBps))
            assertFalse(s.experimentFpsSixThree)
        }
    }

    @Test
    fun `on, in a debug build, the full profile is 6 fps and the reduced one 3, the bitrate and the rest of the stream unchanged`() {
        val s = on.sanitized(debugBuild = true)
        assertTrue(s.experimentFpsSixThree)
        assertEquals(listOf(6, 3), listOf(s.fpsCeiling, s.profileReducedFps))
        assertEquals(DashConfig.PROFILE_FULL_BPS, s.streamBitrateBps)
        assertEquals(400_000, s.effectiveStreamBitrateBps)
        assertEquals(BitrateMode.CBR, s.bitrateMode)
        assertEquals(DashConfig.PROFILE_KEYFRAME_SEC, s.keyframeSec)
        assertEquals(6, REauSettings.lightScreenFps())
        assertEquals("nothing else moves", REauSettings().sanitized(true), s.copy(experimentFpsSixThree = false, fpsCeiling = 4))
    }

    @Test
    fun `in a release build it is always off, whatever was stored, and the stream is the usual one`() {
        val s = on.sanitized(debugBuild = false)
        assertFalse(s.experimentFpsSixThree)
        assertEquals(4, s.fpsCeiling)
        assertEquals(2, s.profileReducedFps)
        assertEquals(REauSettings().sanitized(false), s)
        val stored = on.copy(fpsCeiling = 6)
        assertEquals("a ceiling of 6 stored by a debug build does not survive in a release", 4, stored.sanitized(false).fpsCeiling)
    }

    @Test
    fun `it is stored and read back on its own, and a phone that never stored it reads it off`() {
        val store = SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertFalse(store.load().experimentFpsSixThree)
        store.save(on)
        // the store sanitizes with the build it runs in: on in a debug build, off in a release
        assertEquals(com.reau.app.BuildConfig.DEBUG, store.load().experimentFpsSixThree)
        assertEquals(on.sanitized(), store.load())
        store.save(REauSettings())
        assertFalse(store.load().experimentFpsSixThree)
        assertEquals(4, store.load().fpsCeiling)
        val old = KeystoreBackedPrefsTest.FakePrefs()
        old.edit().putInt("density", 200).apply()
        assertFalse(SettingsStore(old).load().experimentFpsSixThree)
    }

    @Test
    fun `the start guard knows it and switches it off`() {
        assertEquals(listOf("experimentFpsSixThree"), ExperimentsGuard.experimentsOn(on))
        assertEquals(REauSettings(), ExperimentsGuard.allOff(on))
        assertEquals("and the ceiling goes back to 4 with it", 4, ExperimentsGuard.allOff(on.sanitized(true)).sanitized(true).fpsCeiling)
    }

    @Test
    fun `the ride log says it, in the settings line and in the experiments line, and a change of it is told`() {
        assertTrue("experimentFpsSixThree=false" in SettingsInUse.line(REauSettings()))
        assertTrue("experimentFpsSixThree=true" in SettingsInUse.line(on))
        assertTrue("the ceiling it sets is in the line too", "fpsCeiling=6" in SettingsInUse.line(on.sanitized(true)))
        assertNull(SettingsInUse.experimentsLine(REauSettings()))
        val said = SettingsInUse.experimentsLine(on)!!
        assertTrue(said, "video at 6 fps (3 fps in the reduced profile)" in said)
        assertEquals(listOf("experimentFpsSixThree: false -> true"), REauSettings().changesTo(on))
    }

    @Test
    fun `a change of it starts the stream again, since the pace is what changes`() {
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(REauSettings(), on))
        assertEquals(setOf(LiveTuning.Step.NEW_STREAM), LiveTuning.steps(REauSettings().sanitized(true), on.sanitized(true)))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(on, on))
    }

    @Test
    fun `it reaches the video loop with the 6 fps ceiling, and off or in a release it does not`() {
        val loop = VideoLoop.Settings()
        val off = loop.withRider(REauSettings().sanitized(true))
        assertEquals(listOf(4, false, 400_000), listOf(off.ceilingFps, off.exactHalfReduced, off.bitrateBps))
        val s = loop.withRider(on.sanitized(true))
        assertEquals(listOf(6, true, 400_000, 6), listOf(s.ceilingFps, s.exactHalfReduced, s.bitrateBps, s.lightScreenFps))
        assertEquals(false, s.colorMatrix)
        val release = loop.withRider(on.sanitized(false))
        assertEquals(listOf(4, false), listOf(release.ceilingFps, release.exactHalfReduced))
    }

    @Test
    fun `the loop hands it to the governor it builds, and builds the encoder with the governor's rate`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        val start = loop.substringAfter("val g = StreamGovernor(").substringBefore("val rec = recordInto(")
        assertTrue(start, start.contains("exactHalfReduced = settings.exactHalfReduced"))
        assertTrue("the encoder is made for the governor's rate: 6", loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec)"))
        assertTrue(loop.contains("exactHalfReduced = rider.experimentFpsSixThree"))
        assertTrue("the line of the start says it", loop.contains("if (settings.exactHalfReduced) \", 6/3 fps profiles (experiment)\" else \"\""))
    }

    @Test
    fun `the profile rows of the settings say the pace in use, not a fixed 4 and 2`() {
        val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        assertTrue(rows.contains("stringResource(R.string.profiles_fps_sub, s.fpsCeiling, s.profileReducedFps)"))
        assertTrue(rows.contains("R.string.profiles_info, s.fpsCeiling, DashConfig.PROFILE_FULL_BPS / 1000,"))
        assertTrue(rows.contains("s.profileReducedFps, DashConfig.PROFILE_REDUCED_BPS / 1000,"))
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            val sub = Regex("""<string name="profiles_fps_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder: $sub", sub != null && "%1\$d" in sub && "%2\$d" in sub && "4" !in sub)
        }
    }

    @Test
    fun `the row is shown in a debug build only, and has its title and line in the three languages, the line saying Experimental`() {
        val titles = mapOf(
            "values" to "Video at 6 fps (3 fps in the reduced profile)",
            "values-pt" to "Vídeo a 6 fps (3 fps no perfil reduzido)",
            "values-es" to "Video a 6 fps (3 fps en el perfil reducido)",
        )
        for ((folder, title) in titles) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", xml.contains("<string name=\"exp_fps63_title\">$title</string>"))
            val sub = Regex("""<string name="exp_fps63_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder line", sub != null && sub.startsWith("Experimental") && "6" in sub && "3" in sub && "40" in sub)
        }
        val screen = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        assertTrue(Regex("""s\.experimentFpsSixThree, \{ change\(s\.copy\(experimentFpsSixThree = it\)\) \}, "exp_fps63"""").containsMatchIn(screen))
        assertTrue("only in a debug build", screen.contains("if (com.reau.app.BuildConfig.DEBUG) it + fps63Row else it"))
    }
}
