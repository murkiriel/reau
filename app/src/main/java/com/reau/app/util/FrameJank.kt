package com.reau.app.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import com.reau.app.data.RideDiagnostics

/**
 * Measures the interface's slow frames (the logcat of 20/10/2026 said `Skipped 54 frames` at the start and 36 on coming back, with
 * nothing in the ride log to say where): a frame over [THRESHOLD_MS] is one line in the ride log with the phase it happened in
 * (`cold start`, `resume`, `settings`, `running`), at most [MAX_PER_MINUTE] a minute. No dependency: the platform's own frame metrics.
 * The decisions are pure, so the JVM tests cover them.
 */
object FrameJank {
    const val THRESHOLD_MS = 100L
    const val MAX_PER_MINUTE = 5
    const val COLD_START_MS = 5_000L
    const val RESUME_MS = 3_000L
    private const val MINUTE_MS = 60_000L
    private const val NS_PER_MS = 1_000_000L

    /** The settings screens are open: set by the screen, read by the frame callback. */
    @Volatile var settingsOpen = false

    /** The phase a frame belongs to: the start of the process, the return to the screen, the settings, or the rest. */
    fun phaseOf(sinceStartMs: Long, sinceResumeMs: Long, inSettings: Boolean): String = when {
        sinceStartMs in 0 until COLD_START_MS -> "cold start"
        sinceResumeMs in 0 until RESUME_MS -> "resume"
        inSettings -> "settings"
        else -> "running"
    }

    /** Whether a frame that took [totalMs] is worth a line. */
    fun slow(totalMs: Long): Boolean = totalMs > THRESHOLD_MS

    /** The line for a slow frame. */
    fun line(totalMs: Long, phase: String): String = "slow frame: $totalMs ms during $phase"

    /** At most [MAX_PER_MINUTE] lines in any minute. */
    class Limiter(private val clock: () -> Long = MonotonicClock::nowMs) {
        private val told = ArrayDeque<Long>()

        @Synchronized
        fun allow(): Boolean {
            val now = clock()
            while (told.isNotEmpty() && now - told.first() >= MINUTE_MS) told.removeFirst()
            if (told.size >= MAX_PER_MINUTE) return false
            told.addLast(now)
            return true
        }
    }

    /** Starts watching [activity]'s window; [resumedAtMs] gives the monotonic moment of its last resume (0 = never). */
    fun watch(activity: Activity, startedAtMs: Long, resumedAtMs: () -> Long) {
        val limiter = Limiter()
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / NS_PER_MS
            if (slow(total) && limiter.allow()) {
                val now = MonotonicClock.nowMs()
                val resumed = resumedAtMs().let { if (it == 0L) -1L else now - it }
                RideDiagnostics.log("ui", line(total, phaseOf(now - startedAtMs, resumed, settingsOpen)))
            }
        }, Handler(Looper.getMainLooper()))
    }
}
