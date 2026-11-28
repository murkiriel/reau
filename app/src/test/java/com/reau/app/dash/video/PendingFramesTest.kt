package com.reau.app.dash.video

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingFramesTest {
    private val ms = 1_000_000L

    @Test
    fun `each frame comes out with the time it went in with`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 62, nowNs = 0)
        assertEquals(PendingFrames.Emitted(62, late = false), p.emitted(17 * ms, 62))
        p.submitted(framePtsMs = 124, nowNs = 62 * ms)
        assertEquals(PendingFrames.Emitted(124, late = false), p.emitted(80 * ms, 124))
        assertEquals(PendingFrames.Stats(frames = 2, late = 0, latencySumNs = 35 * ms), p.takeStats())
    }

    /** The case this was written for: the encoder had not finished frame 1 when frame 2 went in. Both used
     *  to leave with frame 2's time. */
    @Test
    fun `a frame that comes out after the next one went in keeps its own time and counts as late`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 33, nowNs = 0)
        p.submitted(framePtsMs = 66, nowNs = 33 * ms)
        assertEquals(PendingFrames.Emitted(33, late = true), p.emitted(40 * ms, 33))
        assertEquals(PendingFrames.Emitted(66, late = false), p.emitted(52 * ms, 66))
        assertEquals(PendingFrames.Stats(frames = 2, late = 1, latencySumNs = 59 * ms), p.takeStats())
    }

    @Test
    fun `a frame the encoder swallowed does not shift the ones after it`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 100, nowNs = 0)                      // never comes out
        p.submitted(framePtsMs = 200, nowNs = 1_100 * ms)
        assertEquals(PendingFrames.Emitted(200, late = false), p.emitted(1_117 * ms, 200))
        p.submitted(framePtsMs = 300, nowNs = 1_200 * ms)
        assertEquals(PendingFrames.Emitted(300, late = false), p.emitted(1_217 * ms, 300))
    }

    @Test
    fun `a single old frame is still matched, however long the encoder took`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 250, nowNs = 0)
        assertEquals(PendingFrames.Emitted(250, late = false), p.emitted(3_000 * ms, 250))
    }

    @Test
    fun `an output with nothing pending repeats the last time and is not counted`() {
        val p = PendingFrames()
        assertEquals(PendingFrames.Emitted(0, late = false), p.emitted(5 * ms, 7))
        p.submitted(framePtsMs = 40, nowNs = 10 * ms)
        p.emitted(20 * ms, 40)
        assertEquals(PendingFrames.Emitted(40, late = false), p.emitted(30 * ms, 40))
        assertEquals(40L, p.lastPtsMs())
        assertEquals(PendingFrames.Stats(frames = 1, late = 0, latencySumNs = 10 * ms), p.takeStats())
    }

    @Test
    fun `taking the stats starts a new count`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 1, nowNs = 0); p.emitted(4 * ms, 1)
        p.takeStats()
        assertEquals(PendingFrames.Stats(frames = 0, late = 0, latencySumNs = 0), p.takeStats())
    }

    @Test
    fun `an entry exactly at the stale limit is still matched, one nanosecond past it is skipped`() {
        // Frames 600 ms apart: two ticks are over the limit, so the limit itself decides.
        fun emittedAt(nowNs: Long): PendingFrames.Emitted {
            val p = PendingFrames()
            p.submitted(framePtsMs = 100, nowNs = 0)
            p.submitted(framePtsMs = 200, nowNs = 600 * ms)
            return p.emitted(nowNs, 999)
        }
        assertEquals(PendingFrames.Emitted(100, late = true), emittedAt(PendingFrames.STALE_NS))
        assertEquals(PendingFrames.Emitted(200, late = false), emittedAt(PendingFrames.STALE_NS + 1))
    }

    /** The ride log of 27/11 (MediaTek, 4 fps): three frames dropped on the way into the encoder, the rest answered in 20 ms. */
    @Test
    fun `three frames dropped before the encoder do not shift every later output one slot back`() {
        val p = PendingFrames()
        val sent = listOf(0L, 250, 500, 750, 1000, 1250, 1500, 1750, 2000)
        val dropped = setOf(250L, 500L, 750L)
        val out = mutableListOf<PendingFrames.Emitted>()
        for (pts in sent) {
            p.submitted(framePtsMs = pts, nowNs = pts * ms)
            if (pts !in dropped) out += p.emitted(pts * ms + 20 * ms, pts)
        }
        assertEquals(sent.filter { it !in dropped }, out.map { it.ptsMs })
        assertEquals(List(out.size) { false }, out.map { it.late })
        assertEquals(PendingFrames.Stats(frames = 6, late = 0, latencySumNs = 6 * 20 * ms), p.takeStats())
    }

    @Test
    fun `the time of the frames that come out only goes up`() {
        val p = PendingFrames()
        val sent = (0..9).map { it * 250L }
        val out = mutableListOf<Long>()
        for (pts in sent) {
            p.submitted(framePtsMs = pts, nowNs = pts * ms)
            if (pts % 750L != 250L) out += p.emitted(pts * ms + 20 * ms, pts).ptsMs
        }
        assertEquals(out.sorted().distinct(), out)
    }

    @Test
    fun `a frame swallowed in the middle is skipped and the one after it is matched`() {
        val p = PendingFrames()
        for (pts in listOf(100L, 200, 300, 400)) p.submitted(framePtsMs = pts, nowNs = pts * ms)
        assertEquals(PendingFrames.Emitted(100, late = true), p.emitted(410 * ms, 100))
        assertEquals(PendingFrames.Emitted(300, late = true), p.emitted(420 * ms, 300)) // 200 never came out; 400 is still inside
        assertEquals(PendingFrames.Emitted(400, late = false), p.emitted(430 * ms, 400))
        assertEquals(PendingFrames.Stats(frames = 3, late = 2, latencySumNs = (310 + 120 + 30) * ms), p.takeStats())
    }

    @Test
    fun `a frame matched at the head of the line stays matched however old it is, with a newer one behind`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 100, nowNs = 0)
        p.submitted(framePtsMs = 200, nowNs = 250 * ms)
        assertEquals(PendingFrames.Emitted(100, late = true), p.emitted(700 * ms, 100))
    }

    @Test
    fun `an encoder that rewrites the times is followed by the order of the line`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 5_000, nowNs = 0)
        p.submitted(framePtsMs = 5_033, nowNs = 33 * ms)
        assertEquals(PendingFrames.Emitted(5_000, late = true), p.emitted(40 * ms, 0))
        assertEquals(PendingFrames.Emitted(5_033, late = false), p.emitted(50 * ms, 33))
    }

    @Test
    fun `an encoder that rewrites the times does not keep a swallowed frame at the head for ever`() {
        val p = PendingFrames()
        for (pts in listOf(0L, 250, 500)) p.submitted(framePtsMs = pts, nowNs = pts * ms)
        // Two ticks (500 ms) is the most a frame waits: the two older ones are gone, this one is the answer.
        assertEquals(PendingFrames.Emitted(500, late = false), p.emitted(760 * ms, 1))
    }

    @Test
    fun `a frame in flight for less than two ticks is not taken for a swallowed one`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 0, nowNs = 0)
        p.submitted(framePtsMs = 250, nowNs = 250 * ms)
        assertEquals(PendingFrames.Emitted(0, late = true), p.emitted(300 * ms, 1))
    }

    @Test
    fun `a burst of frames does not make a frame in flight look swallowed`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 0, nowNs = 0)
        p.submitted(framePtsMs = 5, nowNs = 5 * ms)
        assertEquals(PendingFrames.Emitted(0, late = true), p.emitted(30 * ms, 1))
    }

    @Test
    fun `an output no frame was submitted for is not counted and takes no entry`() {
        val p = PendingFrames()
        p.submitted(framePtsMs = 100, nowNs = 0)
        assertEquals(PendingFrames.Emitted(100, late = false), p.emitted(10 * ms, 100))
        assertEquals(PendingFrames.Emitted(100, late = false), p.emitted(20 * ms, 100)) // a second one with the same time: nothing left
        p.submitted(framePtsMs = 200, nowNs = 100 * ms)
        assertEquals(PendingFrames.Emitted(200, late = false), p.emitted(110 * ms, 200))
        assertEquals(PendingFrames.Stats(frames = 2, late = 0, latencySumNs = 20 * ms), p.takeStats())
    }
}
