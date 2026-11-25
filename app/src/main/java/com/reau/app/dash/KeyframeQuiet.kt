package com.reau.app.dash

import com.reau.app.util.MonotonicClock
import kotlinx.coroutines.delay

/**
 * Keeps the routine sends to the dash (the 1 Hz status heartbeat and the clock, the 4 Hz projection frame, the 1 Hz route card and
 * nav packet) away from a keyframe: the dash's link is busiest right after a keyframe burst (ride log of 21/11/2026, where the
 * Wi-Fi hung 0.1 s after a 14 KB keyframe in 11 RTP packets). A routine send asks [waitMs] and, when it is above zero, waits that
 * long: it lands [QUIET_MS] or more after the end of the last keyframe, or after the one still going out.
 *
 * The wait is never more than [MAX_WAIT_MS] for one send, so a keyframe that never reports its end (an encoder that withheld it)
 * cannot hold the routine for good. Only routine sends use this: the answer to the dash's decode ack, the handshake, the button
 * echoes and what the rider's own action sends go out at once. The average pace of the routine does not change; a send only
 * slides by up to [QUIET_MS] (the caller takes the wait from its next delay).
 *
 * The clock is given by the caller (a [MonotonicClock] reading), so the tests need no real time. Thread-safe: the video's output
 * thread reports, the session's coroutines ask.
 */
class KeyframeQuiet(private val clock: () -> Long = MonotonicClock::nowMs) {
    private var startedMs = NONE
    private var endedMs = NONE

    /** A keyframe starts to go out at [nowMs]. */
    @Synchronized
    fun keyframeStarted(nowMs: Long) {
        startedMs = nowMs
    }

    /** The keyframe that was going out finished at [nowMs] (also without a start having been told). */
    @Synchronized
    fun keyframeEnded(nowMs: Long) {
        startedMs = NONE
        endedMs = nowMs
    }

    /**
     * How long a routine send at [nowMs] waits: 0 when no keyframe is near; up to [QUIET_MS] after the end of the last one; while one is going
     * out its end is not known, so [QUIET_MS] (asked again after it), and 0 once it has been "going out" for [MAX_WAIT_MS].
     */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        if (startedMs != NONE) {
            val age = (nowMs - startedMs).coerceAtLeast(0L)
            return if (age >= MAX_WAIT_MS) 0L else QUIET_MS
        }
        if (endedMs == NONE) return 0L
        return (endedMs + QUIET_MS - nowMs).coerceIn(0L, QUIET_MS)
    }

    /**
     * Waits (with [pause], a `delay` in the app) until [waitMs] says a routine send may go, at most [MAX_WAIT_MS] in all. Returns how long it
     * waited in ms (0: it did not), so the caller can take it from its next delay and keep the pace.
     */
    suspend fun await(pause: suspend (Long) -> Unit = { delay(it) }): Long {
        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            val w = minOf(waitMs(clock()), MAX_WAIT_MS - waited)
            if (w <= 0L) break
            pause(w)
            waited += w
        }
        return waited
    }

    companion object {
        /** How far from the end of a keyframe a routine send is kept. */
        const val QUIET_MS = 150L

        /** The most one routine send is held, however long the keyframe seems to go on. */
        const val MAX_WAIT_MS = 300L
        private const val NONE = -1L
    }
}
