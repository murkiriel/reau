package com.reau.app.aa

/**
 * When an Android Auto window coming to the front may make the receiver dial at once. The receiver dials every 2 s, which hid
 * when the server really came up (the 19/10/2026 logs: it answered 0.2 to 1 s after Android Auto's settings came to the front) and
 * delayed the green by up to 2 s after a visit to Android Auto. A window event does not say the server is up, so the dial is
 * only a try (a refused dial costs nothing), at most one a second and [maxPerProjection] a projection.
 */
internal class DialOnWindow(private val minGapMs: Long = 1_000, private val maxPerProjection: Int = 10) {
    private var lastMs = NEVER
    private var count = 0

    /** Whether a dial may go now, at [nowMs] (a monotonic clock); counts it when it may. */
    fun allow(nowMs: Long): Boolean {
        if (count >= maxPerProjection) return false
        if (lastMs != NEVER && nowMs - lastMs < minGapMs) return false
        lastMs = nowMs
        count++
        return true
    }

    /** A new projection begins: its own allowance. */
    fun reset() {
        lastMs = NEVER
        count = 0
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
