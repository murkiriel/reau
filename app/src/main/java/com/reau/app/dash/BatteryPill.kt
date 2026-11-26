package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * The pill of the low battery on the dash's video (plan 35, step E, 26/11/2026): when [com.reau.app.service.BatteryLowWatch] says the phone is at 10 % or under
 * with no charger, the rider also sees it on the dash, in the call card's place and look ([CallCards.batteryNotice]): "Battery 10 %" and, in red, "Plug in the charger".
 * Ride of 25/11/2026: the phone went from 12 % to 4 % in 2 minutes with the screen off in a pocket; the dash is what the rider is looking at.
 *
 * The pill is [SHOW_MS] on the glass, once ([request] says no to a second one: the watch's one-per-projection is kept here too). It gives way: while a call card is on
 * the screen (the call wins), and while the volume card shows (the compositor would draw the volume card over it anyway, and its time would run out unseen). A pill that
 * is held back stays pending and shows when the way is clear; the [SHOW_MS] count only runs while it is on the glass, and starts again if a call or the volume card cuts it.
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
    private var pill: CallNotice? = null
    private var asked = false
    private var onGlassSinceMs: Long? = null
    private var call: CallNotice? = null
    private var volume = false
    private var cancelWait: (() -> Unit)? = null

    /** The rider's pill: false (nothing done) when one was asked for already in this projection; true when it is kept, to show as soon as the way is clear. */
    @Synchronized
    fun request(notice: CallNotice): Boolean {
        if (asked) return false
        asked = true
        pill = notice
        refresh()
        return true
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
        cancelWait?.invoke()
        cancelWait = null
        pill = null
        onGlassSinceMs = null
    }

    /** The wait ran out (on the scheduler's thread): the same look as at any other change. */
    @Synchronized
    private fun waited() = refresh()

    /** What the compositor is given: the call card if there is one, else the pill when the volume card is not up and its time is not out, else nothing. */
    private fun refresh() {
        cancelWait?.invoke()
        cancelWait = null
        val waiting = pill
        val clear = call == null && !volume
        var out = call
        if (waiting != null && clear) {
            val since = onGlassSinceMs ?: nowMs().also { onGlassSinceMs = it }
            val left = since + SHOW_MS - nowMs()
            if (left > 0L) {
                out = waiting
                cancelWait = schedule(left, ::waited)
            } else {
                pill = null
                onGlassSinceMs = null
            }
        } else {
            // Held back by a call or the volume card (or nothing to show): its seconds start again when it is let through.
            onGlassSinceMs = null
        }
        show(out)
    }

    companion object {
        /** How long the pill stays on the glass. */
        const val SHOW_MS = 5_000L
    }
}
