package com.reau.app.dash

/**
 * The engine, told from the dash's telemetry field `0C26` (one byte, sent about every 5 s), for the ride log only: it changes nothing
 * REau does. The meaning is deduced from the logs (strong for "engine running"); the physical quantity itself is not known. Pure, so the
 * JVM tests cover it; one instance per connection ([reset] at the start of each).
 *
 * The state starts unknown and the first reading of a connection sets it ([RUNNING_FROM] or more is running, below [STOPPED_BELOW]
 * is stopped, in between stays unknown). After that: [STOP_READINGS] readings in a row below [STOPPED_BELOW] say it stopped, and
 * [START_READINGS] in a row of [RUNNING_FROM] or more say it started (a start that did not take, one lone reading of 0xB0 or more, as on the ride
 * of 23/11/2026, no longer writes "running" and, ten seconds later, "stopped"). A value of [CRANKING_FROM] or more is said apart (once per rise,
 * at the first reading) and counts as running.
 */
internal class EngineWatch {
    private enum class State(val text: String) { UNKNOWN("unknown"), RUNNING("running"), STOPPED("stopped") }

    private var state = State.UNKNOWN
    private var seenFirst = false
    private var high = false
    private val lows = IntArray(STOP_READINGS)
    private var lowCount = 0
    private var highCount = 0

    /** A new connection: the state is unknown again and the first reading is the first of the connection. */
    fun reset() {
        state = State.UNKNOWN
        seenFirst = false
        high = false
        lowCount = 0
        highCount = 0
    }

    /** One `0C26` reading ([value] 0 to 255); returns the ride log lines it makes (usually none). */
    fun onReading(value: Int): List<String> {
        val out = ArrayList<String>(2)
        val first = !seenFirst
        seenFirst = true
        if (first) {
            state = when {
                value >= RUNNING_FROM -> State.RUNNING
                value < STOPPED_BELOW -> State.STOPPED
                else -> State.UNKNOWN
            }
            out += "engine at connect: ${state.text} (0C26 ${hex(value)})"
        }
        if (value >= CRANKING_FROM) {
            if (!high) out += "engine: 0C26 ${hex(value)} (cranking or external supply)"
            high = true
        } else {
            high = false
        }
        when {
            value >= RUNNING_FROM -> {
                lowCount = 0
                if (highCount < START_READINGS) highCount++
                if (state != State.RUNNING && highCount >= START_READINGS) {
                    state = State.RUNNING
                    out += "engine: running (0C26 ${hex(value)})"
                }
            }
            value < STOPPED_BELOW -> {
                highCount = 0
                if (lowCount == STOP_READINGS) lows.copyInto(lows, 0, 1, STOP_READINGS) else lowCount++
                lows[lowCount - 1] = value
                if (lowCount >= STOP_READINGS && state != State.STOPPED) {
                    state = State.STOPPED
                    out += "engine: stopped (0C26 ${lows.joinToString(", ") { hex(it) }})"
                }
            }
            else -> {
                lowCount = 0
                highCount = 0
            }
        }
        return out
    }

    companion object {
        /**
         * Measured on 366 rides: while riding at 20 km/h or more, 0.1% of the readings fall below 0xB0, so one reading of 0xB0 or more
         * is taken as the engine running.
         */
        const val RUNNING_FROM = 0xB0

        /** Below this a reading counts toward "stopped"; readings from here to [RUNNING_FROM] change nothing (and break a run of low ones). */
        const val STOPPED_BELOW = 0x90

        /**
         * Readings of [RUNNING_FROM] or more in a row to say it started (the owner's decision of 23/11/2026, only the log): one lone reading, a start
         * that did not take, said "running" and ten seconds later "stopped". As many as the readings that say it stopped.
         */
        const val START_READINGS = 2

        /** Measured on 366 rides: 2 readings below 0x90 in a row false-alarm 1.7 times an hour, 1 would be far more. */
        const val STOP_READINGS = 2

        /** From this value on the reading is told apart (the engine cranking or an outside supply: not told apart by this field). */
        const val CRANKING_FROM = 0xF0

        private fun hex(v: Int) = "0x%02X".format(v and 0xFF)
    }
}
