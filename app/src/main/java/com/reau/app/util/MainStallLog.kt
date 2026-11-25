package com.reau.app.util

import java.util.Locale

/**
 * What the ride log says when the app's main thread stops answering (a report of 25/11/2026: Android closed REau in the middle of a ride with
 * `The system loading is too high and ANR is occurring`, and nothing in the log said whether it was REau's main thread that was stuck). The decisions
 * are pure, so the JVM tests cover them: [MainThreadWatch] measures how late the main thread answers and feeds the numbers here.
 *
 * One stall is a few lines: one when the main thread has not answered for [LEVELS_MS]'s first level, one more at each further level (3 s, 5 s: where
 * Android starts an ANR), each with where the main thread is stuck ([stackText]); never the same level twice while the stall lasts; and, when the main
 * thread answers again, one line with how long it took. A stall that ends before the first level writes nothing. Only class and method names are said,
 * never a file, a value or any data.
 */
class MainStallLog {
    /** The highest level already told in the stall now going on; -1 when the main thread is answering. */
    private var told = -1

    /** The line for a main thread that has not answered for [lateMs], or null when this level was told (or none was reached). [stack] is asked only when a line is written. */
    fun waiting(lateMs: Long, stack: () -> String): String? {
        val reached = LEVELS_MS.indexOfLast { lateMs >= it }
        if (reached <= told) return null
        told = reached
        return "main thread not answering for ${seconds(lateMs)} s, at: ${stack()}"
    }

    /** The line for a main thread that has just answered after [lateMs]; null when the stall was too short to be told (and none was). */
    fun answered(lateMs: Long): String? {
        val wasTold = told >= 0
        told = -1
        return if (wasTold || lateMs >= LEVELS_MS.first()) "main thread answered again after ${seconds(lateMs)} s" else null
    }

    companion object {
        /** How late the main thread must be for a line: 1 s, then 3 s and 5 s (the time Android gives an app's main thread before it calls an ANR). */
        val LEVELS_MS = listOf(1_000L, 3_000L, 5_000L)

        /** How many frames of the main thread's stack are said, from the top. */
        const val STACK_FRAMES = 8

        /** The top [STACK_FRAMES] frames as `Class.method < Class.method`, innermost first; only names (no file, no line, no argument). */
        fun stackText(frames: List<StackTraceElement>): String =
            frames.take(STACK_FRAMES).joinToString(" < ") { "${it.className}.${it.methodName}" }.ifEmpty { "no stack" }

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms / MS_PER_S)

        private const val MS_PER_S = 1000.0
    }
}
