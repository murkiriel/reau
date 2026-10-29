package com.reau.app.stream

import com.reau.app.dash.DashConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the video loop decides on its own, with no encoder and no compositor (the loop itself is VideoLoopTest's, on a device). */
class VideoLoopRulesTest {
    private val cool = 0
    private val light = 1
    private val moderate = 2

    @Test
    fun `the compositor draws at once while the input is fresh only with the faster pace set, a phone cooler than moderate and pictures on the dash`() {
        assertEquals(InputBurst.GAP_MS, VideoLoop.burstGapFor(lightScreenFps = 4, thermalStatus = cool, firstFrameConfirmed = true))
        assertEquals(InputBurst.GAP_MS, VideoLoop.burstGapFor(4, light, true))
        assertEquals(InputBurst.GAP_MS, VideoLoop.burstGapFor(4, VideoLoop.THERMAL_UNKNOWN, true))
        assertEquals("the pace is not set", 0L, VideoLoop.burstGapFor(0, cool, true))
        assertEquals("moderate heat", 0L, VideoLoop.burstGapFor(4, moderate, true))
        assertEquals("hotter", 0L, VideoLoop.burstGapFor(4, 4, true))
        assertEquals("the dash shows no picture yet", 0L, VideoLoop.burstGapFor(4, cool, false))
    }

    @Test
    fun `the faster pace of a light screen needs it set, a phone cooler than moderate and the screen saying light`() {
        assertTrue(VideoLoop.lightScreenOn(lightScreenFps = 4, thermalStatus = cool) { true })
        assertTrue(VideoLoop.lightScreenOn(4, light) { true })
        assertFalse("the screen is not light", VideoLoop.lightScreenOn(4, cool) { false })
        assertFalse("not set", VideoLoop.lightScreenOn(0, cool) { true })
        assertFalse("moderate heat", VideoLoop.lightScreenOn(4, moderate) { true })
        assertFalse("a screen that cannot say counts as not light", VideoLoop.lightScreenOn(4, cool) { error("no screen") })
    }

    @Test
    fun `the screen is not asked while the pace is off or the phone is hot`() {
        var asked = 0
        VideoLoop.lightScreenOn(0, cool) { asked++; true }
        VideoLoop.lightScreenOn(4, moderate) { asked++; true }
        assertEquals(0, asked)
        VideoLoop.lightScreenOn(4, cool) { asked++; true }
        assertEquals(1, asked)
    }

    @Test
    fun `the encoder is rebuilt after eight polls in a row that failed, and a good poll starts the count over`() {
        var failed = 0
        repeat(7) { failed = VideoLoop.failedPollsAfter(failed, failedNow = true) }
        assertEquals(7, failed)
        assertFalse("seven polls of 250 ms are not two seconds", VideoLoop.rebuildDue(failed))
        failed = VideoLoop.failedPollsAfter(failed, failedNow = true)
        assertTrue("the eighth", VideoLoop.rebuildDue(failed))
        assertEquals("one good poll", 0, VideoLoop.failedPollsAfter(failed, failedNow = false))
        assertFalse(VideoLoop.rebuildDue(0))
    }

    @Test
    fun `the first frames are recorded only with a folder and a number of frames asked for`() {
        val dir = File("rec")
        assertEquals(dir, VideoLoop.recordInto(dir, 30))
        assertEquals(dir, VideoLoop.recordInto(dir, 1))
        assertNull("no frames asked", VideoLoop.recordInto(dir, 0))
        assertNull("no folder", VideoLoop.recordInto(null, 30))
        assertNull(VideoLoop.recordInto(dir, -1))
    }

    @Test
    fun `the heat is told when it holds the rate under what the rider chose`() {
        assertTrue(VideoLoop.thermalCapped(ceiling = 2, wanted = 4))
        assertFalse(VideoLoop.thermalCapped(ceiling = 4, wanted = 4))
        assertFalse("a ceiling over what was chosen is no cap", VideoLoop.thermalCapped(ceiling = DashConfig.AUTO_FPS_CEILING + 8, wanted = 4))
    }
}
