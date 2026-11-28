package com.reau.app.dash.video

import com.reau.app.util.StageClock
import com.reau.app.util.StageStats

/**
 * Frames handed to the encoder that have not come out of it yet, oldest first — what lets every
 * encoded frame leave with the presentation time of ITS OWN submission when the output is read on
 * another thread, after the frame loop has already moved on. Observed in ride logs: a frame that
 * left a loop late took the next frame's time, two frames with one RTP timestamp.
 *
 * Also measures what the frame loop no longer waits for: how long each frame spent inside the
 * encoder, and how many came out only after a newer one had already gone in ("late").
 *
 * Plain logic with the clock passed in, so it is unit-tested; every method is synchronized because
 * the frame loop submits and the encoder's output thread emits.
 */
internal class PendingFrames {

    data class Emitted(val ptsMs: Long, val late: Boolean)

    data class Stats(val frames: Int, val late: Int, val latencySumNs: Long) {
        /**
         * The same time [latencySumNs] sums, frame by frame: its worst and 95th percentile go to the `video fps` line. Outside the
         * data class's own fields on purpose: what the counts say is still compared as it was.
         */
        var latency: StageStats = StageStats.EMPTY
            internal set

        /** Frames handed to the encoder since the previous call, whether or not they came out. Outside the fields for the same reason. */
        var submitted: Int = 0
            internal set
    }

    private val ptsMs = ArrayDeque<Long>()
    private val submittedNs = ArrayDeque<Long>()
    private var lastPtsMs = 0L
    private var frames = 0
    private var late = 0
    private var latencySumNs = 0L
    private var submittedCount = 0
    private var gapNs = 0L
    private val latencies = StageClock()

    /** Presentation time of the last frame that came out (0 before the first). */
    @Synchronized fun lastPtsMs(): Long = lastPtsMs

    /** One frame is about to be posted to the encoder's input. */
    @Synchronized
    fun submitted(framePtsMs: Long, nowNs: Long) {
        if (submittedNs.isNotEmpty()) gapNs = nowNs - submittedNs.last()
        ptsMs.addLast(framePtsMs)
        submittedNs.addLast(nowNs)
        submittedCount++
    }

    /**
     * One encoded frame came out carrying [outPtsMs], the presentation time the codec gives back in its `BufferInfo` (what the compositor
     * stamped on the frame, in milliseconds: `eglPresentationTimeANDROID` takes ns, the codec returns us). Returns the time it was submitted with.
     *
     * The frame is matched by that time, not by its place in the line: Android may drop frames on the way in (`GraphicBufferSource: Dropping
     * frame that's going backward in time`) and the encoder may swallow others, and each one leaves an entry behind that, in a plain FIFO,
     * shifted every later output one slot back for the rest of the stream (a log at 2 to 4 fps showed `encode=270-530 ms late=20` until the
     * end while the encoder answered normally). So the entries OLDER than the one matched are dropped as swallowed, the matched one is taken, and
     * "late" is only a frame submitted before this output that is still waiting behind it.
     *
     * With no entry of that time (a codec that rewrites the times, or an output whose entry was discarded) it goes by the line's head, as before,
     * with one more cleanup: an entry older than two ticks (see [tickNs]) with a newer one behind it is discarded first, besides the ones
     * older than [STALE_NS].
     */
    @Synchronized
    fun emitted(nowNs: Long, outPtsMs: Long): Emitted {
        val at = ptsMs.indexOf(outPtsMs)
        if (at > 0) repeat(at) { ptsMs.removeFirst(); submittedNs.removeFirst() }
        if (at < 0) {
            val staleNs = minOf(STALE_NS, 2 * tickNs())
            while (ptsMs.size > 1 && nowNs - submittedNs.first() > staleNs) {
                ptsMs.removeFirst(); submittedNs.removeFirst()
            }
        }
        if (ptsMs.isEmpty()) return Emitted(lastPtsMs, late = false)
        lastPtsMs = ptsMs.removeFirst()
        val isLate = ptsMs.isNotEmpty()
        frames++
        if (isLate) late++
        val inEncoderNs = nowNs - submittedNs.removeFirst()
        latencySumNs += inEncoderNs
        latencies.add(inEncoderNs)
        return Emitted(lastPtsMs, isLate)
    }

    /** One tick of the stream as the line shows it: the gap between the last two submissions, never under [MIN_TICK_NS]. */
    private fun tickNs(): Long = maxOf(gapNs, MIN_TICK_NS)

    /** Totals since the previous call, then starts counting again. */
    @Synchronized
    fun takeStats(): Stats = Stats(frames, late, latencySumNs).also {
        it.latency = latencies.take()
        it.submitted = submittedCount
        frames = 0; late = 0; latencySumNs = 0L; submittedCount = 0
    }

    companion object {
        const val STALE_NS = 1_000_000_000L

        /** The shortest tick the cleanup of an unmatched output assumes: a burst of frames must not make a frame in flight look stale. */
        const val MIN_TICK_NS = 40_000_000L
    }
}
