package com.reau.app.stream

import com.reau.app.dash.DashReading
import com.reau.app.dash.DashReport

/**
 * Whether the bike is in the reserve, as the video stream may trust it (decision D117, plan 32: in the reserve the pace is 6 fps or less,
 * [com.reau.app.dash.DashConfig.RESERVE_CAP_FPS]).
 *
 * The dash's low-fuel reminder is `0C05` (`55` on, `AA` off, [DashReport.lowFuel]). [com.reau.app.dash.DashReadings] keeps the last reading of every
 * code between runs, with the time it came, so a reading can be days old: a bike that was in the reserve yesterday and was filled up since
 * would be capped for nothing, and one that was not in the reserve would carry a stale `AA`. Only a reading that came in THIS dash session counts
 * (its time is not before the session's start, which a stream started again inside the session keeps); with no reading, or an older one, there is no ceiling. Pure, so the JVM tests cover it.
 */
internal object ReserveCap {
    /** The code of the low-fuel reminder in [com.reau.app.dash.DashReadings]. */
    const val CODE = "0C05"

    /**
     * Now, in the phone's wall clock: the clock [DashReading.atMs] is kept in, so a session's start can be compared with a reading's time. It is a
     * stamp to compare with another stamp, not a duration (nothing is measured with it), which is why it is read here and not by the monotonic clock.
     */
    fun nowMs(): Long = System.currentTimeMillis()

    /**
     * True only when [reading] says the reminder is ON and came at [sessionStartedAtMs] or later (both in the phone's wall clock, the clock
     * [DashReading.atMs] is kept in). An `AA` (off), a value that is neither, no reading and an older reading are all false.
     */
    fun lowFuel(reading: DashReading?, sessionStartedAtMs: Long): Boolean =
        reading != null && reading.atMs >= sessionStartedAtMs && DashReport.lowFuel(reading.hex) == true
}
