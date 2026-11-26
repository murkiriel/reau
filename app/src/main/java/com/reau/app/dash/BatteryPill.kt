package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * The pill of the low battery on the dash's video (plan 35, step E, 26/11/2026): when [com.reau.app.service.BatteryLowWatch] says the phone is at 10 % or under
 * with no charger, the rider also sees it on the dash, in the call card's place and look ([CallCards.batteryNotice]): "Battery 10 %" and, in red, "Plug in the charger".
 * Ride of 25/11/2026: the phone went from 12 % to 4 % in 2 minutes with the screen off in a pocket; the dash is what the rider is looking at.
 *
 * The owner's decision of 26/11/2026: 5 s was too little. The pill alternates for as long as it is asked for: [SHOW_MS] (1 minute) on the glass, [OUT_MS] (1 minute) off it,
 * on again, and so on. [request] starts it (showing at once when the way is clear; asked again while it runs it only changes the words, the level falls) and [stop] ends it
 * (the charger came in, the battery went up, the dash went, the projection ended); a [request] after a [stop] is a new alternation, showing first. The decision of when to ask and
 * when to stop is [com.reau.app.service.BatteryLowWatch]'s; the timing here.
 *
 * It gives way: while a call card is on the screen (the call wins), and while the volume card shows (the compositor would draw the volume card over it anyway, and its time would
 * run out unseen). A pill that is held back stays pending and shows when the way is clear; the [SHOW_MS] count only runs while it is on the glass, and starts again if a call or the
 * volume card cuts it. The [OUT_MS] count runs as the clock does, call or no call: when it ends with a call up, the pill is pending and shows when the call card goes.
 *
 * Between the call card's feed and the compositor: the feed's card goes through [callChanged], and [show] is what the compositor is given (the call card, the pill, or
 * none). Whoever feeds the volume card tells [volumeChanged]. The clock and the wait ([schedule]: runs the action after the delay and gives back what cancels it) are
 * parameters, so a test moves them by hand; it has no thread of its own.
 */
class BatteryPill(
    private val show: (CallNotice?) -> Unit,
    private val nowMs: () -> Long = MonotonicClock::nowMs,
    private val schedule: (delayMs: Long, action: () -> Unit) -> (() -> Unit) = { _, _ -> {} },
) {
    // Non-null while the alternation runs (on the glass, out of it, or held back by a call or the volume card).
    private var pill: CallNotice? = null
    private var outUntilMs: Long? = null
    private var onGlassSinceMs: Long? = null
    private var call: CallNotice? = null
    private var volume = false
    private var cancelWait: (() -> Unit)? = null

    /**
     * The rider's pill, alternating from now: true when it is taken. A new alternation shows at once (as soon as the way is clear); one that is running only has its words
     * changed to [notice]'s (the level), with its count as it was.
     */
    @Synchronized
    fun request(notice: CallNotice): Boolean {
        if (pill == null) {
            outUntilMs = null
            onGlassSinceMs = null
        }
        pill = notice
        refresh()
        return true
    }

    /** The alternation ends (the charger, the battery up, the dash gone): the pill goes off the glass at once and does not come back until a new [request]. */
    @Synchronized
    fun stop() {
        forget()
        refresh()
    }

    /** What the call card's feed wants on the glass now ([notice], null for none): the compositor is given it as it is, or the pill when no call card is up. */
    @Synchronized
    fun callChanged(notice: CallNotice?) {
        call = notice
        refresh()
    }

    /** The volume card is on the glass ([showing]) or went. */
    @Synchronized
    fun volumeChanged(showing: Boolean) {
        volume = showing
        refresh()
    }

    /** The projection is over: the wait is cancelled and the pill forgotten, and nobody is told (there is nobody left to draw it). */
    @Synchronized
    fun drop() {
        forget()
    }

    private fun forget() {
        cancelWait?.invoke()
        cancelWait = null
        pill = null
        outUntilMs = null
        onGlassSinceMs = null
    }

    /** The wait ran out (on the scheduler's thread): the same look as at any other change. */
    @Synchronized
    private fun waited() = refresh()

    /** What the compositor is given: the call card if there is one, else the pill when it is its time on the glass and the volume card is not up, else nothing. */
    private fun refresh() {
        cancelWait?.invoke()
        cancelWait = null
        val waiting = pill
        var out = call
        var wakeIn = 0L
        if (waiting == null) {
            onGlassSinceMs = null
        } else {
            val now = nowMs()
            val outUntil = outUntilMs?.takeIf { it > now }
            if (outUntil == null) outUntilMs = null
            if (outUntil != null) {
                wakeIn = outUntil - now
            } else if (call == null && !volume) {
                val since = onGlassSinceMs ?: now.also { onGlassSinceMs = it }
                val left = since + SHOW_MS - now
                if (left > 0L) {
                    out = waiting
                    wakeIn = left
                } else {
                    // Its minute on the glass is over: a minute out, counted by the clock whatever is on the screen.
                    onGlassSinceMs = null
                    outUntilMs = now + OUT_MS
                    wakeIn = OUT_MS
                }
            } else {
                // Held back by a call or the volume card: its minute starts again when it is let through.
                onGlassSinceMs = null
            }
        }
        if (wakeIn > 0L) cancelWait = schedule(wakeIn, ::waited)
        show(out)
    }

    companion object {
        /** How long the pill stays on the glass each time (the owner's decision of 26/11/2026: 1 minute, it was 5 s). */
        const val SHOW_MS = 60_000L

        /** How long it stays off the glass between two times, while the alternation runs. */
        const val OUT_MS = 60_000L
    }
}
