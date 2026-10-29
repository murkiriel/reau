package com.reau.app.aa

/**
 * When the accessibility service reads Android Auto's screen on a window event. Every event used to walk the windows and, during a
 * walk, up to 600 nodes on the main thread, several times a second while Android Auto redraws (audit of 20/10/2026). The rule:
 * read only when something needs the reading (a walk is on, or the projection runs and its screen is followed), and not more
 * often than once every [minGapMs]; an event that comes inside the gap is not lost, it is read when the gap ends (once, however many
 * came). Pure and not thread-safe: the service's main thread drives it.
 */
class ReadGate(private val minGapMs: Long = MIN_GAP_MS) {
    private var lastMs = Long.MIN_VALUE / 2
    private var pending = false

    /** What to do with an event at [nowMs]. */
    sealed interface Verdict {
        /** Nothing needs the reading. */
        data object Skip : Verdict

        /** Read now. */
        data object Now : Verdict

        /** Read after [afterMs] (one read is already planned: [Later.first] false). */
        data class Later(val afterMs: Long, val first: Boolean) : Verdict
    }

    fun onEvent(needed: Boolean, nowMs: Long): Verdict {
        if (!needed) return Verdict.Skip
        val since = nowMs - lastMs
        if (since >= minGapMs) return Verdict.Now
        val first = !pending
        pending = true
        return Verdict.Later(minGapMs - since, first)
    }

    /** A read was made at [nowMs] (by an event, by the planned wait, or by the poll). */
    fun read(nowMs: Long) {
        lastMs = nowMs
        pending = false
    }

    /** The service was unbound or connected anew: nothing is planned any more (its planned reads were removed with it). */
    fun reset() {
        lastMs = Long.MIN_VALUE / 2
        pending = false
    }

    companion object {
        const val MIN_GAP_MS = 100L
    }
}
