package com.reau.app.video

import java.util.Locale

/**
 * What the debug build's ride log says of the picture classifier ([PictureLook], [ScreenNet]), so that what the offline replica cannot reproduce
 * (it is not bit for bit the app: another decoder and another reduction; 04/11/2026 07:41:04, the app read "menu" at a p the replica put under
 * the limit) can be audited from the log: one line when the class of the look changes, and one when the net is not sure of itself (p under
 * [LOW_P]), so a call card laid over the map (92 to 95 % for 33 s) cannot flood the log. Three limits, none of which loses the count:
 * the second kind at most one per [lowGapMs] and [lowPerMinute] in each minute, and [maxLines] of both in all. What is left out is counted, and the
 * next line says how many (`N looks not written`); they are all of the class of the line before (a change of class is always written), which is how
 * the reader knows how many were of the unsure class, so its rate of "not sure" stays exact when the minute's lines run out. A projection of an hour
 * has room: the old limit (300 lines in all) stopped one of 52 minutes after 14.
 * Pure: the caller gives the look and the clock.
 */
internal class LookAudit(
    private val maxLines: Int = MAX_LINES,
    private val lowGapMs: Long = LOW_GAP_MS,
    private val lowPerMinute: Int = LOW_PER_MINUTE,
) {
    private var last: PictureLook.Kind? = null
    private var lastLowAtMs = Long.MIN_VALUE / 2
    private var skipped = 0
    private var lines = 0
    private var stopped = false
    private var minuteStartMs = Long.MIN_VALUE / 2
    private var lowsInMinute = 0

    /** The line for this look ([p]: the net's confidence, 0 when the picture was gated as black), or null when there is nothing to say. */
    fun line(kind: PictureLook.Kind, p: Float, nowMs: Long): String? {
        val before = last
        last = kind
        val changed = before != kind
        val low = p > 0f && p < LOW_P
        if (!due(changed, low, nowMs)) return null
        if (low) {
            lastLowAtMs = nowMs
            lowsInMinute++
        }
        return write(kind, p, reason(before, changed, low))
    }

    private fun write(kind: PictureLook.Kind, p: Float, why: String): String {
        if (lines >= maxLines) return stop()
        lines++
        return String.format(Locale.US, "picture look: %s p=%.3f (%s)", kind, p, why)
    }

    /**
     * Whether a look is to be written: its class changed, or the net was not sure, the last such line is a second old and the minute still has
     * room for it; never after the limit. A class change is never held back by the minute (it is the rare kind and the point of the audit).
     */
    private fun due(changed: Boolean, low: Boolean, nowMs: Long): Boolean {
        if (!changed && !low) return false
        if (low && nowMs - minuteStartMs >= MINUTE_MS) {
            minuteStartMs = nowMs
            lowsInMinute = 0
        }
        if (!changed && (nowMs - lastLowAtMs < lowGapMs || lowsInMinute >= lowPerMinute)) {
            skipped++
            return false
        }
        return !stopped
    }

    private fun stop(): String {
        stopped = true
        return "picture look: $maxLines lines written, no more in this session"
    }

    private fun reason(before: PictureLook.Kind?, changed: Boolean, low: Boolean): String {
        val why = buildString {
            if (changed) append("was ${before ?: "nothing"}")
            if (changed && low) append(", ")
            if (low) append("p under $LOW_P")
            if (skipped > 0) append("; $skipped looks not written")
        }
        skipped = 0
        return why
    }

    companion object {
        /** A look whose confidence is under this is written (the abstention limit of the net is 0.9; the study's candidate for a new one, 0.98). */
        const val LOW_P = 0.98f
        const val LOW_GAP_MS = 1_000L

        /** Lines of a net that is not sure, from the same class, per minute: 20 is a third of what one a second would write, and still shows the minute. */
        const val LOW_PER_MINUTE = 20

        /** In all, a safety: an hour at the minute's limit is 1 200 lines (about 140 KB), so this covers over two hours of a net that is never sure. */
        const val MAX_LINES = 3_000
        private const val MINUTE_MS = 60_000L
    }
}
