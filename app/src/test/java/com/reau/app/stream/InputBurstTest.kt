package com.reau.app.stream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long the rider's input counts as fresh, for drawing Android Auto's answer at once ([FreshInput]). */
class InputBurstTest {
    @Test
    fun `nothing is fresh before the first input`() {
        assertFalse(FreshInput(1_800).fresh(0))
        assertFalse(FreshInput(1_800).fresh(5_000))
    }

    @Test
    fun `an input is fresh for the window and then stops being`() {
        val input = FreshInput(1_800)
        input.sent(10_000)
        assertTrue(input.fresh(10_000))
        assertTrue(input.fresh(11_800))
        assertFalse(input.fresh(11_801))
    }

    @Test
    fun `a new input renews the window`() {
        val input = FreshInput(1_800)
        input.sent(10_000)
        input.sent(11_500)
        assertTrue(input.fresh(13_300))
        assertFalse(input.fresh(13_301))
    }

    @Test
    fun `the picture is looked at four times a second, ten right after an input, for three seconds`() {
        assertEquals(250L, InputBurst.lookEveryMs(lookQuick = false))
        assertEquals(100L, InputBurst.lookEveryMs(lookQuick = true))
        val input = FreshInput(InputBurst.LOOK_QUICK_WINDOW_MS)
        input.sent(10_000)
        assertTrue(input.fresh(13_000))
        assertFalse(input.fresh(13_001))
    }

    @Test
    fun `the burst gap is at least one frame of the profile's pace, never the 80 ms alone`() {
        assertEquals("6 fps (REau)", 167L, InputBurst.gapMs(6))
        assertEquals("4 fps (Original)", 250L, InputBurst.gapMs(4))
        assertEquals("a hot start at 4 fps", 250L, InputBurst.gapMs(4))
        assertTrue(InputBurst.gapMs(6) >= 167L)
        assertTrue(InputBurst.gapMs(4) >= 250L)
        assertEquals("12 fps is 84 ms, over the floor", 84L, InputBurst.gapMs(12))
        assertEquals("a pace above 12 falls to the floor", InputBurst.GAP_MS, InputBurst.gapMs(30))
        assertEquals("no pace known: the floor", InputBurst.GAP_MS, InputBurst.gapMs(0))
        assertEquals(InputBurst.GAP_MS, InputBurst.gapMs(-1))
    }

    /** A new picture every millisecond through the whole window of a burst: the frames the compositor draws at once, by the wait it computes. */
    private fun burstFrames(profileFps: Int): List<Long> {
        val gap = VideoLoop.burstGapFor(lightScreenFps = profileFps, thermalStatus = 0, firstFrameConfirmed = true, profileFps = profileFps)
        val frames = mutableListOf(1_000L)
        var now = 1_000L
        while (now < 1_000L + FreshInput.FRESH_MS) {
            now += 1
            val wait = com.reau.app.video.CompositorMath.burstWaitMs(newImage = true, lastFrameAtMs = frames.last(), nowMs = now, gapMs = gap)
            if (wait == 0L) frames += now
        }
        return frames
    }

    @Test
    fun `no frame in the window of a burst comes closer than the profile's pace`() {
        for ((fps, least) in listOf(6 to 167L, 4 to 250L)) {
            val frames = burstFrames(fps)
            val intervals = frames.zipWithNext { a, b -> b - a }
            assertTrue("$fps fps: ${intervals.min()} ms", intervals.all { it >= least })
            // The frames after the first, in the 1.8 s: never more than the pace allows.
            assertTrue("$fps fps: ${frames.size - 1} frames in ${FreshInput.FRESH_MS} ms", frames.size - 1 <= fps * FreshInput.FRESH_MS / 1_000)
        }
    }

    @Test
    fun `the loop hands the compositor the pace's gap for the burst and for the info ring`() {
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("burstGap(status, g.encoderFps)"))
        assertTrue(loop.contains("compositor.frameGapFloorMs = InputBurst.gapMs(g.encoderFps)"))
        assertEquals("at the poll and at the start", 2, Regex("""frameGapFloorMs = InputBurst\.gapMs\(g\.encoderFps\)""").findAll(loop).count())
        val compositor = File("src/main/java/com/reau/app/video/DashCompositor.kt").readText()
        assertTrue(compositor.contains("val gapMs = maxOf(CompositorMath.RING_REDRAW_GAP_MS, frameGapFloorMs)"))
    }
}
