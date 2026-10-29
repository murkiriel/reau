package com.reau.app.aa

/**
 * When the walk that switches Android Auto's server off may begin after a projection ended. In the logs of 19 and 20/10/2026 the
 * Android Auto process crashed (a NullPointerException on its ServerSocket) 0.64 to 1.19 s after REau's bye, three times in nine
 * stops, each with Android Auto's settings opened by that walk within 126 ms of the bye: the server closes its socket while the
 * settings screen resumes and starts it again. The walk now waits [SETTLE_MS] after the session ended. (The server is running
 * then, so its port stays open and cannot be the sign to go earlier.) Pure, so the JVM tests cover it.
 */
internal object StopWalkGate {
    /** How long after the session ended the stop walk waits. */
    const val SETTLE_MS = 2_000L

    /** The wait that remains when the session ended [sinceEndMs] ago; never negative nor longer than [SETTLE_MS] (a clock that went back). */
    fun waitMs(sinceEndMs: Long): Long = (SETTLE_MS - sinceEndMs).coerceIn(0L, SETTLE_MS)
}
