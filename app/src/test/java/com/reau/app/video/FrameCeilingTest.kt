package com.reau.app.video

import com.reau.app.stream.InputBurst
import java.io.File
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ceiling of the video, 6 frames a second (fpsCeiling 6, the REAU pace): no way of drawing may take the dash above it. The ride of 23/11/2026 (08:19 to 08:20, on
 * calls) showed 7 to 7.6 fps with `repeated` at 4 to 6 in 5 s: the call card's time, redrawn once a second off the tick ([DashCompositor.redrawSoon]), came a moment
 * after the tick that fell on the same instant, one frame over the pace for each redraw. The compositor's scheduling (a handler's queue, the tick's grid, the draws off
 * the tick) is run here by the same rules ([CompositorRules], [CompositorMath]) on a clock of the test's own, and the source is read to see the wiring.
 */
class FrameCeilingTest {
    private enum class Kind { TICK, RING_DRAW, BURST_DRAW }

    private class Event(val atMs: Long, val seq: Long, val kind: Kind) : Comparable<Event> {
        override fun compareTo(other: Event) = compareValuesBy(this, other, { it.atMs }, { it.seq })
    }

    /** What one second of the compositor does, as [DashCompositor] does it; [superseded] is the cancel of a pending draw by any frame. */
    private class Sim(val fps: Int, val superseded: Boolean, val guarded: Boolean = superseded, val frameMs: Long = 4L) {
        val pace = CompositorRules.normalized(DashCompositor.Pace(Math.round(1_000.0 / fps), 2 * Math.round(1_000.0 / fps)))
        val gap = InputBurst.gapMs(fps)
        val queue = PriorityQueue<Event>()
        var seq = 0L
        var now = 0L
        var newImage = false
        var sinceLast = 0L
        var tickSpan = 0L
        var nextTick = 0L
        var lastFrameAt = 0L
        var pending = false
        val frames = mutableListOf<Long>()

        fun post(at: Long, kind: Kind) { queue.add(Event(at, seq++, kind)) }

        fun cancelPending() {
            queue.removeAll { it.kind != Kind.TICK }
            pending = false
        }

        fun frame() {
            frames += now
            lastFrameAt = now + frameMs
            sinceLast = 0L
            newImage = false
            if (superseded) cancelPending()
        }

        fun start() {
            sinceLast = pace.idleMs
            post(0L, Kind.TICK)
        }

        /** The info ring changed (a notice's time): [DashCompositor.redrawSoon]. */
        fun redrawSoon() {
            if (pending) return
            pending = true
            post(now + CompositorMath.redrawWaitMs(lastFrameAt, now, maxOf(CompositorMath.RING_REDRAW_GAP_MS, gap)), Kind.RING_DRAW)
        }

        /** A new picture while the rider's input is fresh: [DashCompositor.drawSoon]. */
        fun pictureWhileFresh() {
            newImage = true
            val wait = CompositorMath.burstWaitMs(true, lastFrameAt, now, gap) ?: return
            if (pending) return
            pending = true
            post(now + wait, Kind.BURST_DRAW)
        }

        fun picture() { newImage = true }

        private fun offTick() {
            queue.removeAll { it.kind == Kind.TICK }
            sinceLast = now - lastFrameAt
            frame()
            tickSpan = pace.activeMs
            nextTick = now + tickSpan
            post(nextTick, Kind.TICK)
        }

        /** Runs the queue up to [untilMs], with [outside] called at every millisecond first (the requests and the pictures). */
        fun run(untilMs: Long, outside: (Long) -> Unit) {
            while (now <= untilMs) {
                outside(now)
                while (queue.isNotEmpty() && (queue.peek()?.atMs ?: Long.MAX_VALUE) <= now) {
                    val e = queue.poll() ?: break
                    when (e.kind) {
                        Kind.TICK -> {
                            sinceLast += tickSpan
                            when (CompositorRules.tickAction(newImage, false, sinceLast, pace)) {
                                TickAction.DRAW, TickAction.REPEAT -> frame()
                                TickAction.WAIT -> Unit
                            }
                            tickSpan = pace.activeMs
                            nextTick = CompositorRules.nextTickAt(nextTick, tickSpan, now)
                            post(nextTick, Kind.TICK)
                        }
                        Kind.RING_DRAW -> {
                            pending = false
                            val wait = if (guarded) CompositorMath.redrawWaitMs(lastFrameAt, now, maxOf(CompositorMath.RING_REDRAW_GAP_MS, gap)) else 0L
                            if (wait > 0) { pending = true; post(now + wait, Kind.RING_DRAW) } else offTick()
                        }
                        Kind.BURST_DRAW -> {
                            pending = false
                            val wait = if (guarded) CompositorMath.redrawWaitMs(lastFrameAt, now, gap) else 0L
                            if (!newImage) Unit else if (wait > 0) { pending = true; post(now + wait, Kind.BURST_DRAW) } else offTick()
                        }
                    }
                }
                now++
            }
        }

        /** The most frames in any window of [windowMs] (a frame at the start counts, one at the end does not). */
        fun mostIn(windowMs: Long): Int = frames.indices.maxOf { i -> frames.count { it >= frames[i] && it < frames[i] + windowMs } }
    }

    /** A call: its time is redrawn once a second, at [phase] of the second; Android Auto's pictures come every 33 ms. */
    private fun callRide(sim: Sim, phase: Long, seconds: Int = 40, bursts: Boolean = false) {
        sim.start()
        sim.run(seconds * 1_000L) { t ->
            if (t % 33L == 0L) {
                if (bursts && t / 1_800 % 2 == 0L) sim.pictureWhileFresh() else sim.picture()
            }
            if (t % 1_000L == phase) sim.redrawSoon()
        }
    }

    @Test
    fun `at 6 fps no redraw for a notice's time takes the video above 6 frames in any second, whenever in the tick it falls`() {
        for (phase in 0L until 167L step 3L) {
            val sim = Sim(fps = 6, superseded = true)
            callRide(sim, phase)
            assertTrue("phase $phase: ${sim.mostIn(1_000)} frames in a second", sim.mostIn(1_000) <= 6)
            val seconds = sim.frames.last() / 1_000.0
            assertTrue("phase $phase: ${sim.frames.size} frames in $seconds s", sim.frames.size / seconds <= 6.05)
        }
    }

    @Test
    fun `with the input burst on as well, the 6 are still the most`() {
        for (phase in 0L until 167L step 11L) {
            val sim = Sim(fps = 6, superseded = true)
            callRide(sim, phase, bursts = true)
            assertTrue("phase $phase: ${sim.mostIn(1_000)} frames in a second", sim.mostIn(1_000) <= 6)
            val intervals = sim.frames.zipWithNext { a, b -> b - a }
            assertTrue("phase $phase: closest ${intervals.min()} ms", intervals.min() >= 160L)
        }
    }

    @Test
    fun `the test is able to see the fault, the pending draw left standing and no gap kept`() {
        val worst = (0L until 167L step 3L).maxOf { phase ->
            val sim = Sim(fps = 6, superseded = false, guarded = false)
            callRide(sim, phase)
            sim.frames.size / (sim.frames.last() / 1_000.0)
        }
        assertTrue("without the cancel the rate goes above 6: $worst", worst > 6.5)
    }

    @Test
    fun `each of the two guards alone already holds the ceiling, so one that breaks leaves the other`() {
        for ((superseded, guarded) in listOf(true to false, false to true)) {
            for (phase in 0L until 167L step 5L) {
                val sim = Sim(fps = 6, superseded = superseded, guarded = guarded)
                callRide(sim, phase, bursts = true)
                assertTrue("cancel=$superseded gap=$guarded phase $phase: ${sim.mostIn(1_000)}", sim.mostIn(1_000) <= 6)
            }
        }
    }

    @Test
    fun `the 4 fps pace keeps its own ceiling too`() {
        for (phase in 0L until 250L step 13L) {
            val sim = Sim(fps = 4, superseded = true)
            callRide(sim, phase)
            assertTrue("phase $phase: ${sim.mostIn(1_000)} frames in a second", sim.mostIn(1_000) <= 4)
        }
    }

    // ── the wiring in the compositor ──

    private val compositor = File("src/main/java/com/reau/app/video/DashCompositor.kt").readText()

    @Test
    fun `every frame cancels the draws off the tick still waiting, and a draw off the tick waits for the gap it is owed`() {
        val post = compositor.substringAfter("private fun drawAndPost(").substringBefore("private val webIdle")
        assertTrue("the cancel comes after the frame is counted", post.contains("countFrame(repeat)\n        // This frame drew everything as it is: a draw off the tick still waiting would only be a frame over the pace.\n        cancelOffTick()"))
        val cancel = compositor.substringAfter("private fun cancelOffTick()").substringBefore("private val ringDraw")
        assertTrue(cancel.contains("removeCallbacks(ringDraw)") && cancel.contains("removeCallbacks(burstDraw)") && cancel.contains("burstPending = false"))
        assertTrue("the output's end cancels them too", compositor.substringAfter("private fun dropOutput()").substringBefore("private val tick").contains("cancelOffTick()"))
        assertEquals("the ring's draw and the burst's each keep the gap before drawing", 2, Regex("""waitedForGap\(this, """).findAll(compositor).count())
        assertTrue(compositor.contains("waitedForGap(this, maxOf(CompositorMath.RING_REDRAW_GAP_MS, frameGapFloorMs))"))
        assertTrue(compositor.contains("waitedForGap(this, burstGapMs)"))
    }

    @Test
    fun `the video's ceiling and pace are the 6 fps the dash takes`() {
        assertEquals(6, com.reau.app.settings.VideoPace.REAU.fullFps)
        assertEquals(167L, InputBurst.gapMs(6))
    }
}
