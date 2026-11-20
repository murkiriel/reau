package com.reau.app.aa

/**
 * How long Android Auto is left without video focus when REau asks it for a keyframe (the focus is taken away and given back: the
 * protocol has no request for a keyframe). 100 ms is what REau always did; a 400 ms trial (a switch, until 20/11/2026) was never needed, no
 * stall of the Android Auto decoder having been seen to compare. What a cycle bought is what [FocusCycleTiming] measures.
 */
internal object FocusGap {
    const val DEFAULT_MS = 100L
}

/**
 * Measures what a focus cycle bought: the time from the cycle to the next keyframe of Android Auto, written once per cycle, and a line when
 * none came in [OVERDUE_MS]. Only words for the log: nothing is decided from it. Called from the read thread and the session's timer.
 */
internal class FocusCycleTiming {
    private var cycledAtMs: Long? = null
    private var gapMs = 0L
    private var overdueSaid = false

    /** A cycle went out at [nowMs] with [gapMs] between the loss and the gain. */
    @Synchronized
    fun cycled(nowMs: Long, gapMs: Long) {
        cycledAtMs = nowMs
        this.gapMs = gapMs
        overdueSaid = false
    }

    /** A keyframe arrived at [nowMs]: the line for the log when it is the first since a cycle, else null. */
    @Synchronized
    fun keyframe(nowMs: Long): String? {
        val at = cycledAtMs ?: return null
        cycledAtMs = null
        return "focus cycle: the keyframe came ${nowMs - at} ms after the cycle (focus given back after $gapMs ms)"
    }

    /** Asked every second: the line, once per cycle, when no keyframe came [OVERDUE_MS] after it; else null. */
    @Synchronized
    fun overdue(nowMs: Long): String? {
        val at = cycledAtMs ?: return null
        if (overdueSaid || nowMs - at < OVERDUE_MS) return null
        overdueSaid = true
        return "focus cycle: no keyframe ${nowMs - at} ms after the cycle (focus given back after $gapMs ms)"
    }

    companion object {
        /** Android Auto answers a cycle within a second or two when it answers at all. */
        const val OVERDUE_MS = 5_000L
    }
}
