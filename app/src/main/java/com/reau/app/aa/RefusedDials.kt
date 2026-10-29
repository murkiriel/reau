package com.reau.app.aa

/**
 * The dials of one [AaReceiver] loop that found no connection, for the line the log says when one comes at last: the
 * log only names the first refusal, so how many there were and for how long would otherwise not be known (the wait
 * after a stop, Android Auto's server coming up). [callIn]: Android Auto 17.3 and older, where the receiver asks
 * for a call instead of dialling.
 */
internal class RefusedDials(private val callIn: Boolean) {
    /** How many tries in a row found none. */
    var count = 0
        private set
    private var sinceMs = 0L
    private var saidMs = 0L

    /** A try found no connection, at [nowMs] (elapsed realtime). */
    fun refused(nowMs: Long) {
        if (count == 0) {
            sinceMs = nowMs
            saidMs = nowMs
        }
        count++
    }

    /**
     * The line to log while the dials keep finding nothing, at most once every [SAY_EVERY_MS] (null: nothing to say yet). Until 25/10/2026 the
     * log said the first refusal and then nothing until a connection came: two Lite runs of that day ended with the rider turning it off
     * after 8 to 10 s of silence, and the log could not say whether the dials went on.
     */
    fun progress(nowMs: Long): String? {
        if (count == 0 || nowMs - saidMs < SAY_EVERY_MS) return null
        saidMs = nowMs
        val what = if (callIn) "ask(s) for a call unanswered" else "dial(s) refused"
        val tookMs = nowMs - sinceMs
        return "still no connection: $count $what in ${tookMs / 1_000}.${tookMs % 1_000 / 100} s, going on"
    }

    /** A connection came at [nowMs]: the line to log if tries were refused before it (and the count starts again), else null. */
    fun connected(nowMs: Long): String? {
        if (count == 0) return null
        val tookMs = nowMs - sinceMs
        val what = if (callIn) "ask(s) for a call unanswered" else "dial(s) refused"
        return "connected at last after $count $what in ${tookMs / 1_000}.${tookMs % 1_000 / 100} s".also { count = 0 }
    }

    private companion object {
        const val SAY_EVERY_MS = 10_000L
    }
}
