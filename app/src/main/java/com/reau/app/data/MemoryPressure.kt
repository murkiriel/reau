package com.reau.app.data

/**
 * The system's signs that memory is short (`onTrimMemory`, `onLowMemory`), for the ride log (plan 35, a report of 25/11/2026: Android closed REau for low memory at
 * 16:10:18 and 16:10:55 and the log had not a line about it before, so nobody could say whether the system had warned). Each sign is written with the name of its level
 * and makes the projection look at the phone at once ([attach]: the `device …` line of the same function that writes the 30 s ones). A sign that says the app should
 * let go of memory ([isPressure]: running low, running critical, complete, and `onLowMemory`) also makes the looks of the projection write a line at every look
 * (5 s) for the next [BOOST_MS], then back to the 30 s: a closing by the system is not announced for long, and a line every 30 s was too far from it.
 *
 * It reads and starts nothing: the levels' numbers are written here ([ComponentCallbacks2][android.content.ComponentCallbacks2]'s own constants are deprecated since
 * Android 14, and a warning is an error in this build).
 */
class PressureSignals(private val say: (String) -> Unit) {
    @Volatile private var signalAtMs = NONE
    @Volatile private var look: ((String) -> Unit)? = null

    /** The projection's look at the phone, called on any thread with the reason it is written for (`trim RUNNING_LOW`); it replaces the one before. */
    fun attach(listener: (String) -> Unit) {
        look = listener
    }

    /** The projection let go of its look; only the one that is attached is taken away (an old service still releasing must not take away the new one's). */
    fun detach(listener: (String) -> Unit) {
        if (look === listener) look = null
    }

    /** Android sent `onTrimMemory([level])` at [nowMs] (a monotonic reading). */
    fun trimmed(level: Int, nowMs: Long) {
        val name = levelName(level)
        runCatching { say("onTrimMemory($name, $level)" + if (isPressure(level)) ": $BOOST_NOTE" else "") }
        if (isPressure(level)) signalAtMs = nowMs
        lookNow("trim $name")
    }

    /** Android sent `onLowMemory()` at [nowMs]. */
    fun lowMemory(nowMs: Long) {
        runCatching { say("onLowMemory(): $BOOST_NOTE") }
        signalAtMs = nowMs
        lookNow("low-memory")
    }

    /** How often the device line is written at [nowMs] because of a sign of pressure, or null when no sign came in the last [BOOST_MS] (the usual period stands). */
    fun periodAt(nowMs: Long): Long? = BOOST_PERIOD_MS.takeIf { signalAtMs != NONE && nowMs - signalAtMs in 0..BOOST_MS }

    private fun lookNow(why: String) {
        runCatching { look?.invoke(why) }
    }

    companion object {
        /** How long after a sign of pressure the device line is written at every look, and how often. */
        const val BOOST_MS = 60_000L
        const val BOOST_PERIOD_MS = 5_000L
        private const val NONE = Long.MIN_VALUE
        private const val MS_PER_S = 1_000L
        private const val BOOST_NOTE = "memory is short, the device lines come every ${BOOST_PERIOD_MS / MS_PER_S} s for ${BOOST_MS / MS_PER_S} s"

        // ComponentCallbacks2.TRIM_MEMORY_*: 5, 10 and 15 while the app is in the foreground, 20 for its screens hidden, 40, 60 and 80 while it sits in the background list.
        private const val RUNNING_MODERATE = 5
        private const val RUNNING_LOW = 10
        private const val RUNNING_CRITICAL = 15
        private const val UI_HIDDEN = 20
        private const val BACKGROUND = 40
        private const val MODERATE = 60
        private const val COMPLETE = 80

        /** The name of the level as Android writes it in its documentation, without the prefix; `level<N>` for a number it does not know. */
        fun levelName(level: Int): String = when (level) {
            RUNNING_MODERATE -> "RUNNING_MODERATE"
            RUNNING_LOW -> "RUNNING_LOW"
            RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            UI_HIDDEN -> "UI_HIDDEN"
            BACKGROUND -> "BACKGROUND"
            MODERATE -> "MODERATE"
            COMPLETE -> "COMPLETE"
            else -> "level$level"
        }

        /** Whether the level says the app is to let go of memory because the phone is short of it: running low, running critical and complete. */
        fun isPressure(level: Int): Boolean = level == RUNNING_LOW || level == RUNNING_CRITICAL || level == COMPLETE
    }
}

/** The one set of signs of the process (the `Application` hears them, the projection's service looks at the phone for them). */
object MemoryPressure {
    val signals = PressureSignals { RideDiagnostics.log("memory", it) }
}
