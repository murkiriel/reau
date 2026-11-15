package com.reau.app.aa

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Switching the projection off and on quickly brought Android Auto down, sometimes with "Already connected": its
 * server was still closing the session the bye had ended. The first dial after a stop waits until [GAP_MS] have
 * passed since the stop.
 *
 * The instant of the stop also counts the window of [PreviousSessionClosing]. It lives in memory (elapsed realtime) and, as
 * two numbers in a private file, for a process that dies and comes back inside the window: Android Auto goes on closing
 * the old session whatever happens to REau's process.
 *
 * The end of the last session, whoever ended it ([lastEndedAtMs]: REau's stop, or Android Auto leaving with no stop of REau's), is kept the same
 * way, in two numbers of its own: [AaWake] counts from it how long Android Auto has been left alone. It is not the stop, and nothing above
 * (the second before a dial, the closing window) counts from it.
 */
// The state is process-wide and has two doors for each of its calls: the one the receiver uses (a Context) and the one the JVM tests use (the file given).
@Suppress("TooManyFunctions")
object AaRestartGap {
    const val GAP_MS = 1_000L

    private const val FILE = "aa_restart_gap"
    private const val KEY_WALL = "stopped_wall_ms"
    private const val KEY_ELAPSED = "stopped_elapsed_ms"
    private const val KEY_ENDED_WALL = "ended_wall_ms"
    private const val KEY_ENDED_ELAPSED = "ended_elapsed_ms"

    /** When the last receiver stopped (elapsed realtime); process-wide, as each projection builds its own receiver. */
    @Volatile var lastStoppedAtMs: Long? = null

    /**
     * When the last session ended, with REau's stop or without it (elapsed realtime); process-wide like [lastStoppedAtMs], and restored from the
     * file by the first receiver of a process. Null: not known. It is the instant [AaWake] counts from.
     */
    @Volatile var lastEndedAtMs: Long? = null

    /**
     * Where the doubt of [PreviousSessionClosing.maybeClosing] counts from (elapsed realtime): the first receiver of this
     * process to start dialling since a handshake was last answered or the port last refused a dial. A session REau did not
     * see end (the other edition's) may have ended just before it, and nothing in the private file above can say so, as
     * each edition has its own. Process-wide so that a receiver started again inside the same mute stretch does not
     * renew the doubt: a server that stays mute past the window is stuck.
     */
    @Volatile var maybeClosingSinceMs: Long? = null

    // The file is read once per process, at the first receiver; tests that set the instant by hand call [reset].
    @Volatile private var restoredThisProcess = false

    fun delayMs(nowMs: Long, stoppedAtMs: Long?): Long =
        if (stoppedAtMs == null) 0L else (stoppedAtMs + GAP_MS - nowMs).coerceAtLeast(0L)

    /** A receiver stopped now: kept in memory and in the file (and it is the end of the last session too). */
    fun stopped(context: Context, nowElapsedMs: Long, nowWallMs: Long) = stopped(prefs(context), nowElapsedMs, nowWallMs)

    internal fun stopped(saved: SharedPreferences, nowElapsedMs: Long, nowWallMs: Long) {
        lastStoppedAtMs = nowElapsedMs
        lastEndedAtMs = nowElapsedMs
        restoredThisProcess = true
        saved.edit {
            putLong(KEY_WALL, nowWallMs)
            putLong(KEY_ELAPSED, nowElapsedMs)
            putLong(KEY_ENDED_WALL, nowWallMs)
            putLong(KEY_ENDED_ELAPSED, nowElapsedMs)
        }
    }

    /**
     * A session whose handshake was answered ended now without REau's stop (Android Auto left, its socket broke): kept in memory and in the file as
     * the last end. The stop above is not touched: the second before a dial and the closing window count from REau's own stops only.
     */
    fun sessionEnded(context: Context, nowElapsedMs: Long, nowWallMs: Long) = sessionEnded(prefs(context), nowElapsedMs, nowWallMs)

    internal fun sessionEnded(saved: SharedPreferences, nowElapsedMs: Long, nowWallMs: Long) {
        lastEndedAtMs = nowElapsedMs
        restoredThisProcess = true
        saved.edit {
            putLong(KEY_ENDED_WALL, nowWallMs)
            putLong(KEY_ENDED_ELAPSED, nowElapsedMs)
        }
    }

    /**
     * The first receiver of a process that has no stop in memory takes the one kept by the process before, if it is
     * still believable ([PreviousSessionClosing.restoredStop]). Returns a line for the log, or null when there was nothing to say.
     */
    fun restore(context: Context, nowElapsedMs: Long, nowWallMs: Long): String? = restore(prefs(context), nowElapsedMs, nowWallMs)

    internal fun restore(saved: SharedPreferences, nowElapsedMs: Long, nowWallMs: Long): String? {
        val first = !restoredThisProcess
        restoredThisProcess = true
        // The end of the last session comes back by the same rule as the stop (the two clocks must agree about the time since), whatever the stop says.
        val keptEnd = saved.contains(KEY_ENDED_WALL) && saved.contains(KEY_ENDED_ELAPSED)
        if (first && lastEndedAtMs == null && keptEnd) {
            lastEndedAtMs = PreviousSessionClosing.restoredStop(
                saved.getLong(KEY_ENDED_WALL, 0L), saved.getLong(KEY_ENDED_ELAPSED, 0L), nowWallMs, nowElapsedMs,
            )
        }
        val kept = saved.contains(KEY_WALL) && saved.contains(KEY_ELAPSED)
        if (!first || lastStoppedAtMs != null || !kept) return null
        val stop = PreviousSessionClosing.restoredStop(
            saved.getLong(KEY_WALL, 0L), saved.getLong(KEY_ELAPSED, 0L), nowWallMs, nowElapsedMs,
        )
        lastStoppedAtMs = stop
        return when {
            stop == null -> "the stop kept by the process before cannot be trusted (the phone restarted or the clock moved); no window"
            PreviousSessionClosing.within(nowElapsedMs, stop) ->
                "the process before stopped the receiver ${(nowElapsedMs - stop) / 1_000} s ago; counting the previous session's closing from it"
            else -> "the process before stopped the receiver ${(nowElapsedMs - stop) / 1_000} s ago; past the window"
        }
    }

    /** A receiver begins to dial at [nowMs]: the doubt of [maybeClosingSinceMs] begins now unless one is already running. */
    fun dialBegun(nowMs: Long) {
        if (maybeClosingSinceMs == null) maybeClosingSinceMs = nowMs
    }

    /** The port refused a dial: the server that was closing a session is gone, so there is nothing left to doubt. */
    fun portRefused() {
        maybeClosingSinceMs = null
    }

    /** A handshake was answered: the old session is not what Android Auto answers now, so a later process has no window to count. */
    fun answered(context: Context) {
        maybeClosingSinceMs = null
        prefs(context).edit { clear() }
    }

    /** For tests: no stop to count from, in memory or in the file, and none read back. */
    fun reset(context: Context) = reset(prefs(context))

    internal fun reset(saved: SharedPreferences) {
        maybeClosingSinceMs = null
        lastStoppedAtMs = null
        lastEndedAtMs = null
        restoredThisProcess = true
        saved.edit { clear() }
    }

    /** For tests: a new process, which knows nothing in memory and has yet to read the file. */
    internal fun testOnlyNewProcess() {
        maybeClosingSinceMs = null
        lastStoppedAtMs = null
        lastEndedAtMs = null
        restoredThisProcess = false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
