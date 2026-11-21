package com.reau.app.data

/**
 * What the `device …` line says of the battery's current and charge, from the raw numbers Android's BatteryManager gives. A phone
 * that cannot say a value answers `Int.MIN_VALUE` (or `Long.MIN_VALUE`), and one without a charge counter answers 0: those are null here,
 * written as `-` in the line, never a made-up number. The sign of the current is kept as the phone gives it (it varies by maker).
 */
object PowerReadings {
    private const val MICRO_PER_MILLI = 1000L

    /** `BATTERY_PROPERTY_CURRENT_NOW` (µA) in mA, or null when the phone could not say. */
    fun currentMa(microAmps: Int): Int? = if (microAmps == Int.MIN_VALUE) null else currentMa(microAmps.toLong())

    fun currentMa(microAmps: Long): Int? = if (microAmps == Long.MIN_VALUE) null else (microAmps / MICRO_PER_MILLI).toInt()

    /** `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh) in mAh, or null when the phone could not say or has no counter (0). */
    fun chargeMah(microAmpHours: Int): Int? = if (microAmpHours == Int.MIN_VALUE) null else chargeMah(microAmpHours.toLong())

    fun chargeMah(microAmpHours: Long): Int? =
        if (microAmpHours == Long.MIN_VALUE || microAmpHours == 0L) null else (microAmpHours / MICRO_PER_MILLI).toInt()
}

/**
 * The process's CPU use between two looks, in percent of ONE core (it can pass 100): the CPU time the process used since the look before
 * ([android.os.Process.getElapsedCpuTime], ms) over the wall-clock time that passed ([android.os.SystemClock.elapsedRealtime], ms). Pure, so it is
 * tested on the JVM. Null when there is nothing to compare: the first look, a window of 0 ms or less, a clock or a CPU time that went back.
 * Whatever the answer, this look is the one the next is compared with.
 */
class CpuWindow {
    private var lastCpuMs: Long? = null
    private var lastClockMs = 0L

    fun offer(cpuMs: Long, clockMs: Long): Int? {
        val beforeCpu = lastCpuMs
        val beforeClock = lastClockMs
        lastCpuMs = cpuMs
        lastClockMs = clockMs
        val window = clockMs - beforeClock
        val used = cpuMs - (beforeCpu ?: return null)
        return if (window <= 0L || used < 0L) null else Math.round(PERCENT * used / window.toDouble()).toInt()
    }

    private companion object {
        const val PERCENT = 100.0
    }
}
