package com.reau.app.data

import java.io.File
import java.util.Locale

/**
 * What `/proc/meminfo` (`MemAvailable`) and `/proc/pressure/memory` (the kernel's pressure stall information, PSI) say, for the `device …` line's `memavail=` and
 * `psi=` (plan 35, a report of 25/11/2026: Android closed REau for low memory and the log had nothing before it). `phone=` is what Android's own
 * `ActivityManager.MemoryInfo.availMem` says, which is not the kernel's `MemAvailable`; PSI is what says the phone was stalling for memory before the kill. Both are one
 * short read of a file, on the thread that already looks at the phone; no new thread and nothing of other processes.
 *
 * `/proc/meminfo` is readable by an app; `/proc/pressure/memory` often is not (SELinux, or a kernel with no PSI): it is tried at the first look, and a file that cannot be
 * read (or says something that is not the `some` line) is never tried again, with one note saying so ([onPressureUnreadable]) so that the log's reader knows why the
 * line has no `psi=`. A failure of the meminfo read leaves `memavail=` out of that line only.
 */
class MemoryProc(
    private val meminfo: () -> String? = { File(MEMINFO).readText() },
    private val pressureFile: () -> String? = { File(PRESSURE).readText() },
    private val onPressureUnreadable: () -> Unit,
) {
    private var pressureUnreadable = false

    /** The kernel's `MemAvailable` in MB, or null when the file cannot be read or has no such line. */
    fun availableMb(): Int? = runCatching { meminfo() }.getOrNull()?.let(::parseAvailableMb)

    /** `0.00/0.00/0.00` (the `some` line's avg10, avg60 and avg300, in percent of the time stalled), or null when the file cannot be read now or ever. */
    fun pressure(): String? {
        if (pressureUnreadable) return null
        val parsed = runCatching { pressureFile() }.getOrNull()?.let(::parsePressure)
        if (parsed == null) {
            pressureUnreadable = true
            runCatching(onPressureUnreadable)
        }
        return parsed
    }

    companion object {
        const val MEMINFO = "/proc/meminfo"
        const val PRESSURE = "/proc/pressure/memory"

        /** The ride log's note, once per projection, when the pressure file cannot be read; it does not start with `device ` (the reader takes those for the looks at the phone). */
        const val UNREADABLE_LINE = "memory pressure not readable by the app on this phone ($PRESSURE): no psi= in the device lines"

        private val AVAILABLE = Regex("(?m)^MemAvailable:\\s+(\\d+)\\s*kB")
        private val SOME = Regex("(?m)^some\\s+avg10=([0-9.]+)\\s+avg60=([0-9.]+)\\s+avg300=([0-9.]+)")
        private const val KB_PER_MB = 1024L

        /** `MemAvailable: 1830400 kB` in the text of `/proc/meminfo` as MB; null with no such line. */
        fun parseAvailableMb(text: String): Int? =
            AVAILABLE.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { (it / KB_PER_MB).toInt() }

        /** The three averages of the `some` line of `/proc/pressure/memory` (`some avg10=0.12 avg60=0.05 avg300=0.01 total=123`) as `0.12/0.05/0.01`; null with no such line. */
        fun parsePressure(text: String): String? {
            val m = SOME.find(text) ?: return null
            val numbers = m.groupValues.drop(1).map { it.toDoubleOrNull() }
            if (numbers.any { it == null || it.isNaN() || it.isInfinite() }) return null
            return numbers.joinToString("/") { String.format(Locale.US, "%.2f", it) }
        }
    }
}
