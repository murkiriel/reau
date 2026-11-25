package com.reau.app.aa

/**
 * Waking Android Auto before dialling it. Always on (a switch until 20/11/2026): the first negotiation of a projection failed in 10 of 62
 * projections without it and in none of 48 with it (ride logs of 11 to 20/11/2026).
 *
 * With its battery use restricted, Android freezes Android Auto's projection process about 10 s after each session and keeps it frozen for
 * 1 to 44 minutes (measured in the logs of 04 to 14/11/2026). In that time the phone's kernel still accepts the connection to the head unit
 * server and nobody answers it; when Android Auto thaws, it calls its frozen car process and falls over. A dial made inside that window is
 * the one that finds Android Auto frozen, and REau was the trigger of the fall in 2 of 23 starts that had a log. Opening Android Auto's own
 * settings (what the app already does when the server is off) brings its process out of the freeze; the dial then waits [WAIT_MS] for it.
 *
 * [due] is the window: a session that ended more than [SETTLE_MS] ago (before that Android has not frozen it, and REau's own second before a
 * dial, [AaRestartGap.GAP_MS], is the only wait) and less than [WINDOW_MS] ago (past that, the freezes seen were over). The wake is made at most
 * twice in a projection: once before the first dial, if [due], and once, whatever the clock says, after a negotiation that got no answer at all
 * ([muteNegotiation]); both come right before a dial ([dialing]), which then follows at once.
 *
 * [enabled] is read at each decision (a call-in Android Auto, 17.3 and older, is not dialled, so it is not woken). [wake] opens Android Auto's settings, [pause] waits [waitMs] and [say] writes the ride log.
 */
internal class AaWake(
    private val enabled: () -> Boolean,
    private val wake: () -> Unit,
    private val pause: (Long) -> Unit,
    private val say: (String) -> Unit,
    private val waitMs: Long = WAIT_MS,
) {
    private var firstDial = true
    private var muteLeft = true
    private var muteSeen = false

    /** A negotiation just ended with no answer from Android Auto: the next dial is preceded by one wake, the first time in a projection. */
    fun muteNegotiation() {
        if (muteLeft) muteSeen = true
    }

    /**
     * One dial of the projection: the wake first, if one is due (and the wait after it), then [dial], at once. [nowMs] and [endedAtMs] are read when the dial
     * begins, in elapsed realtime ([AaRestartGap.lastEndedAtMs]).
     */
    fun <T> dialing(nowMs: () -> Long, endedAtMs: () -> Long?, dial: () -> T): T {
        beforeDial(nowMs(), endedAtMs())
        return dial()
    }

    private fun beforeDial(nowMs: Long, endedAtMs: Long?) {
        val first = firstDial
        firstDial = false
        if (!enabled()) return
        val why = when {
            first && due(nowMs, endedAtMs) ->
                "before the first dial of this projection: the last session ended ${ago(nowMs, endedAtMs)} ago, inside the window " +
                    "in which Android has frozen Android Auto (restricted battery)"
            muteSeen && muteLeft -> {
                muteLeft = false
                muteSeen = false
                "after a negotiation that got no answer from Android Auto (it may be frozen by Android); last session end: ${endedAgo(nowMs, endedAtMs)}"
            }
            else -> return
        }
        say("waking Android Auto $why; its settings open and the dial waits $waitMs ms")
        // A wake that fails is no reason not to dial: the dial is what the projection is for.
        runCatching { wake() }.onFailure { say("the wake failed (${it.javaClass.simpleName}); the dial goes on") }
        pause(waitMs)
    }

    private fun ago(nowMs: Long, endedAtMs: Long?) = "${((nowMs - (endedAtMs ?: nowMs)) / MS_PER_SECOND)} s"

    private fun endedAgo(nowMs: Long, endedAtMs: Long?) = if (endedAtMs == null) "not known" else "${ago(nowMs, endedAtMs)} ago"

    companion object {
        /** A session that ended no longer ago than this has not been frozen yet (Android freezes it about 10 s after). */
        const val SETTLE_MS = 8_000L

        /** The longest freeze seen was 44 minutes: past this the window is over. */
        const val WINDOW_MS = 45L * 60L * 1_000L

        /** How long the dial waits after the wake. */
        const val WAIT_MS = 700L

        private const val MS_PER_SECOND = 1_000L

        /**
         * Whether a dial at [nowMs] is inside the window after the end at [endedAtMs]: more than [SETTLE_MS] and less than [WINDOW_MS] since
         * (both elapsed realtime). No end known, or a clock that went backwards (the end after now), is no window.
         */
        fun due(nowMs: Long, endedAtMs: Long?): Boolean {
            if (endedAtMs == null) return false
            val since = nowMs - endedAtMs
            return since > SETTLE_MS && since < WINDOW_MS
        }
    }
}
