package com.reau.app.data

import java.io.File
import java.util.Locale

/**
 * The phone's load average (`/proc/loadavg`: the runnable tasks over the last 1, 5 and 15 minutes) for the `device …` line, to say whether the whole
 * phone was heavily loaded when Android closed REau for "the system loading is too high" (a report of 25/11/2026). In many Android versions an app is
 * not allowed to read that file: it is tried at the first look, and a file that cannot be read (or says something that is not three numbers) is never tried
 * again, with one note saying so ([onUnreadable]) so that the log's reader knows why the line has no `load=`. It reads only that file: nothing of other
 * processes (no `/proc/stat`, no `/proc/<pid>`).
 */
class LoadAverage(
    private val source: () -> String? = { File(PATH).readText() },
    private val onUnreadable: () -> Unit,
) {
    private var unreadable = false

    /** `1.20/0.80/0.50` (the three averages), or null when the file cannot be read now or ever. */
    fun read(): String? {
        if (unreadable) return null
        val parsed = runCatching { source() }.getOrNull()?.let(::parse)
        if (parsed == null) {
            unreadable = true
            runCatching(onUnreadable)
        }
        return parsed
    }

    companion object {
        const val PATH = "/proc/loadavg"

        /** The ride log's note, once per projection, when the file cannot be read; it does not start with `device ` (the reader takes those for the looks at the phone). */
        const val UNREADABLE_LINE = "load average not readable by the app on this phone ($PATH): no load= in the device lines"

        /** The first three numbers of the file's text (`0.52 0.58 0.59 1/467 8132`) as `0.52/0.58/0.59`; null when they are not three non-negative numbers. */
        fun parse(text: String): String? {
            val numbers = text.trim().split(Regex("\\s+")).take(LOADS).map { it.toDoubleOrNull() }
            if (numbers.size < LOADS || numbers.any { it == null || it !in 0.0..Double.MAX_VALUE }) return null
            return numbers.joinToString("/") { String.format(Locale.US, "%.2f", it) }
        }

        private const val LOADS = 3
    }
}
