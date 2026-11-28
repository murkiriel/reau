package com.reau.app.dash

/**
 * The dash's telemetry field `09 08` (one byte), told in the ride log only when it changes: it changes nothing REau does. Its meaning is a
 * guess from the ride logs of 956 readings (the study of 22/11/2026): [GEAR_GUESS_ON] in every reading at 60 km/h or more, [GEAR_GUESS_OFF] in
 * 95 % of the readings with the engine stopped and in 49 of 56 starts, the first [GEAR_GUESS_ON] coming 3 to 406 s after the start. It would be
 * "in gear" against "neutral or clutch pulled", but nothing proves it, which is why the lines say "?" and the constants say "guess". The log
 * lines are there to confirm it with more rides. Pure, so the JVM tests cover it; one instance per connection ([reset] at the start of each).
 */
internal class GearGuessWatch {
    private var last: Int? = null
    private var sinceMs = 0L

    /** A new connection: the next reading is the first of it. */
    fun reset() {
        last = null
    }

    /**
     * One `09 08` reading ([value] 0 to 255) at [nowMs] (monotonic), with the speed the navigation gives when it gives one ([speedKmh]); returns the
     * ride log line when the value is the first of the connection or differs from the last one, null when it is the same.
     */
    fun onReading(value: Int, nowMs: Long, speedKmh: Double?): String? {
        val before = last
        if (before == value) return null
        last = value
        val since = sinceMs
        sinceMs = nowMs
        val speed = speedKmh?.let { ", nav speed %d km/h".format(java.util.Locale.US, Math.round(it)) }.orEmpty()
        val after = if (before == null) {
            " (first reading of the connection)"
        } else {
            " after ${name(before)} for %.1f s".format(java.util.Locale.US, (nowMs - since) / MS_PER_S)
        }
        return "09 08: ${name(value)}$after$speed"
    }

    private fun name(value: Int): String = when (value) {
        GEAR_GUESS_ON -> "AA (in gear?)"
        GEAR_GUESS_OFF -> "55 (neutral or clutch?)"
        else -> "0x%02X (unknown)".format(value and 0xFF)
    }

    companion object {
        /** `AA`: guessed to be "in gear" (956 readings of the ride logs, no proof). */
        const val GEAR_GUESS_ON = 0xAA

        /** `55`: guessed to be "neutral, or the clutch pulled" (956 readings of the ride logs, no proof). */
        const val GEAR_GUESS_OFF = 0x55

        private const val MS_PER_S = 1000.0
    }
}
