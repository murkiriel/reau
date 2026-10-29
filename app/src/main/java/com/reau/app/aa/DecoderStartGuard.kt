package com.reau.app.aa

/**
 * What the video decoder does after a start that failed.
 *
 * `decode()` tries to start a decoder on every picture while there is none, so a codec that refuses to configure
 * (a busy hardware decoder, a wrong surface) was tried again 30 times a second, each try creating a codec. Now a failure
 * waits [backoffMs] before the next try (250 ms, 1 s, 4 s, then 10 s), and from the [SOFTWARE_AFTER]th failure in a row
 * the software decoder is asked for instead of the hardware one. A start that works, or a new surface, begins again.
 *
 * Called only with the decoder's lock held.
 */
internal class DecoderStartGuard {
    private var failures = 0
    private var retryAtMs = 0L

    /** True when the next start should ask for the software decoder. */
    val useSoftware: Boolean get() = failures >= SOFTWARE_AFTER

    val failuresInARow: Int get() = failures

    /**
     * Whether to ask the decoder for its low-latency mode (`KEY_LOW_LATENCY`, Android 11+): on the first try only. A decoder that
     * refuses to configure with it is tried again without it, then in software.
     */
    val lowLatency: Boolean get() = failures == 0

    fun mayTry(nowMs: Long): Boolean = failures == 0 || nowMs >= retryAtMs

    fun failed(nowMs: Long) {
        failures++
        retryAtMs = nowMs + backoffMs(failures)
    }

    fun worked() = reset()

    fun reset() {
        failures = 0
        retryAtMs = 0L
    }

    companion object {
        const val SOFTWARE_AFTER = 2

        /** The wait after the [failures]th failure in a row. */
        fun backoffMs(failures: Int): Long = when {
            failures <= 1 -> 250L
            failures == 2 -> 1_000L
            failures == 3 -> 4_000L
            else -> 10_000L
        }
    }
}
