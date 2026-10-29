package com.reau.app.aa

/**
 * At most one log line a [minGapMs] for something that changes often: the `aa-screen displays:` line was written 224 times in a day, up to
 * every second for 15 minutes (the launcher and the navigation display taking the focus in turn, with the dash away). The line that goes out
 * says how many changes it stands for; the changes that fell in between are counted, not written (the lines of the screen that held, from
 * [AaScreenWatch], are written as before). Pure, so the JVM tests cover it. Not thread-safe: the reading thread drives it.
 */
class LineThrottle(private val minGapMs: Long = MIN_GAP_MS) {
    private var lastMs = Long.MIN_VALUE / 2
    private var held = 0

    /** What to write for [text] at [nowMs], or null to write nothing now. */
    fun offer(nowMs: Long, text: String): String? {
        if (nowMs - lastMs < minGapMs) {
            held++
            return null
        }
        lastMs = nowMs
        val more = held
        held = 0
        return if (more == 0) text else "$text (+$more more changes in the last ${minGapMs / MS_PER_S} s, not written)"
    }

    companion object {
        const val MIN_GAP_MS = 1_000L
        private const val MS_PER_S = 1_000L
    }
}
