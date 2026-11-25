package com.reau.app.dash.nav

import com.reau.app.dash.protocol.DashCommands

/**
 * Keeps the time left and the arrival time on the dash from flickering by one minute (ride of 25/11/2026).
 *
 * Android Auto's two figures are each rounded to the minute on its side, and they land on the dash as they come: the time left
 * went 7, 8 for one second, 7, 7... and the arrival 06:59, 07:00, 06:59 (07:00 for 15 s of every minute). Nothing is wrong with
 * either figure to a minute, but a value that goes up and down by one for a second looks like a fault on the dash. So a figure is
 * moved to a new value only when it is clearly a different one:
 *  - the time left goes down at once (a normal route loses one minute per minute, and one down is never doubted), goes up by two
 *    minutes or more at once (a recalculation), and goes up by one only when it has stayed one up for [REMAINING_HOLD_MS];
 *  - the arrival time moves by two minutes or more at once, and by one only when it has stayed there for [ARRIVAL_HOLD_MS], longer
 *    than the longest stay of the other value of the flicker (45 s in the ride) so it never lasts that long.
 *
 * One instance per guidance: [reset] when the guidance ends, so the next route starts from its own figures. Times are the
 * monotonic milliseconds of the caller.
 */
class ArrivalSmoother {
    private var shownMinutes: Long? = null
    private var risingSinceMs = NONE

    private var shownArrival: Int? = null
    private var candidateArrival: Int? = null
    private var candidateSinceMs = 0L

    @Synchronized
    fun reset() {
        shownMinutes = null
        risingSinceMs = NONE
        shownArrival = null
        candidateArrival = null
        candidateSinceMs = 0L
    }

    /** The time left [seconds] ([DashCommands.REMAINING_OMIT] when unknown, which is left as it is) as it goes to the dash, to the minute. */
    @Synchronized
    fun remaining(seconds: Long, nowMs: Long): Long {
        if (seconds < 0) return seconds
        val raw = seconds / SECONDS_PER_MINUTE
        val shown = shownMinutes ?: raw
        // One minute up (two or more is a recalculation, and a minute down is never doubted): it shows only after it has lasted.
        val oneUp = raw > shown && raw - shown < BIG_STEP
        if (oneUp && risingSinceMs == NONE) risingSinceMs = nowMs
        val held = oneUp && nowMs - risingSinceMs < REMAINING_HOLD_MS
        if (!held) {
            shownMinutes = raw
            risingSinceMs = NONE
        }
        return if (held) shown * SECONDS_PER_MINUTE else seconds
    }

    /**
     * The arrival time [text] ("08:01", "2:35 PM", "0801") as it goes to the dash: the text itself when it is to be shown, the shown
     * minute as "HH:MM" when the figure is held back. A text that is no time of day goes as it is, and changes nothing.
     */
    @Synchronized
    fun arrival(text: String?, nowMs: Long): String? {
        val digits = DashCommands.etaDigits(text) ?: return text
        val raw = digits.substring(0, 2).toInt() * MINUTES_PER_HOUR + digits.substring(2).toInt()
        val shown = shownArrival ?: raw
        // A minute either way (two or more is a real change): it shows only after it has lasted, counted from when it began.
        val oneAway = raw != shown && kotlin.math.abs(minutesBetween(shown, raw)) < BIG_STEP
        if (oneAway && candidateArrival != raw) {
            candidateArrival = raw
            candidateSinceMs = nowMs
        }
        val held = oneAway && nowMs - candidateSinceMs < ARRIVAL_HOLD_MS
        if (!held) {
            shownArrival = raw
            candidateArrival = null
        }
        return if (held) "%02d:%02d".format(java.util.Locale.US, shown / MINUTES_PER_HOUR, shown % MINUTES_PER_HOUR) else text
    }

    /** The minutes from [from] to [to] on the clock, by the shorter way round the day: 23:59 to 00:01 is 2, not -1438. */
    private fun minutesBetween(from: Int, to: Int): Int = ((to - from + HALF_DAY_MINUTES) % DAY_MINUTES + DAY_MINUTES) % DAY_MINUTES - HALF_DAY_MINUTES

    companion object {
        /** A rise of the time left by one minute shows after it has lasted this long (the flicker lasted one second). */
        const val REMAINING_HOLD_MS = 30_000L

        /** A move of the arrival time by one minute shows after it has lasted this long (the flicker's longest stay was 45 s). */
        const val ARRIVAL_HOLD_MS = 75_000L

        /** A move of this many minutes, either way, is a real change and shows at once. */
        const val BIG_STEP = 2

        private const val NONE = Long.MIN_VALUE
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60
        private const val DAY_MINUTES = 1_440
        private const val HALF_DAY_MINUTES = 720
    }
}
