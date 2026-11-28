package com.reau.app.data

import java.util.Locale

/**
 * How long a resumed projection took to put the picture back on the dash (plan 35, a report of 25/11/2026: the system ended REau, and the resume that
 * the 0.4.0 does by itself had no number to say what it gained over the rider tapping Ligar again). The service [arm]s it when it resumes after the
 * system ended the process; the dash session calls [confirmed] when the dash confirms the first frame, and the line it returns says how many seconds had
 * passed since the system ended the process (the exit Android kept, [ExitHistory]) and since this process started. It is told once: a projection that
 * ends without a frame, or a frame that comes after [MAX_WAIT_MS], is not told, so a projection the rider starts later never takes it for its own.
 * Everything is in the same monotonic clock ([com.reau.app.util.MonotonicClock]); nothing here reads a wall clock.
 */
class ResumeClock {
    private data class Armed(val atMs: Long, val processAgeMs: Long, val exitAgeMs: Long?, val exitName: String?)

    private var armed: Armed? = null

    /**
     * The resume began at [nowMs]: the process had started [processAgeMs] before, and the system ended the one before [exitAgeMs] before ([exitName], the
     * exit's reason as the log writes it; both null when Android did not keep it, or kept an exit too old or too new to be the one that ended the ride).
     */
    @Synchronized
    fun arm(nowMs: Long, processAgeMs: Long, exitAgeMs: Long?, exitName: String?) {
        armed = Armed(nowMs, processAgeMs.coerceAtLeast(0L), exitAgeMs?.takeIf { it in 0..MAX_EXIT_AGE_MS }, exitName)
    }

    /** The projection ended, or was started by the rider: nothing is waited for. */
    @Synchronized
    fun disarm() {
        armed = null
    }

    /** The dash confirmed the first frame at [nowMs]: the line for the log, once; null when no resume is waiting. */
    @Synchronized
    fun confirmed(nowMs: Long): String? {
        val wait = armed ?: return null
        armed = null
        val since = nowMs - wait.atMs
        return if (since in 0..MAX_WAIT_MS) lineFor(wait, since) else null
    }

    private fun lineFor(wait: Armed, sinceMs: Long): String {
        val process = seconds(wait.processAgeMs + sinceMs)
        val exit = wait.exitAgeMs?.let { seconds(it + sinceMs) }
            ?: return "resumed projection: the dash confirmed the first frame $process s after this process started " +
                "(the time the system ended the one before is not known)"
        return "resumed projection: the dash confirmed the first frame $exit s after the system ended the process" +
            (wait.exitName?.let { " ($it)" } ?: "") + " and $process s after this process started"
    }

    companion object {
        /** A resume that has not had its first frame by then is not waited for. */
        const val MAX_WAIT_MS = 10 * 60_000L

        /** An exit older than the resume's own limit (the mark lives 30 minutes) is not the one that ended this ride. */
        const val MAX_EXIT_AGE_MS = 35 * 60_000L
        private const val MS_PER_S = 1_000.0

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms / MS_PER_S)

        /** The one clock of the process (one projection at a time). */
        val shared = ResumeClock()
    }
}
