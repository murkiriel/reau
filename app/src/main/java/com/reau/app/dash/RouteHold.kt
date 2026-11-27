package com.reau.app.dash

/**
 * The route card and the nav packet held back after the rider confirmed "Exit navigation?" on the dash itself (its 0x12): both carry the
 * projection flag 06 05 = 0x55, which puts the dash back in its navigation, so while it is held none of them goes (ride of 26/11/2026: the card
 * resent 5 ms after the answer kept the dash in the navigation). The rest of what goes to the dash (the status, the music and the call cards,
 * the video) is not touched.
 *
 * It ends when a NEW guidance starts, that is, when Android Auto's guidance was inactive for at least [newGuidanceAfterMs] and then is active again.
 * Android Auto keeps guiding after the dash's own exit (REau tells it nothing), so a guidance that never went inactive is the old one; and a
 * recalculation reaches REau as an inactive status for 1 to 7 s between two active ones ([com.reau.app.aa.NavGuidance.INACTIVE_BY_STATUS]), which
 * is no new guidance: [newGuidanceAfterMs] is longer than that.
 *
 * Every send of the card or the packet goes through [sendUnlessHeld], and the hold starts through [hold], under one lock, so a card made a moment
 * before the exit never reaches the wire after the "navigation off".
 */
class RouteHold(private val newGuidanceAfterMs: Long = NEW_GUIDANCE_AFTER_MS) {
    private val lock = Any()
    private var held = false
    private var inactiveSinceMs: Long? = null

    /** Whether the card and the packet are held now. */
    val isHeld: Boolean get() = synchronized(lock) { held }

    /**
     * Starts the hold and runs [send] (the "navigation off" to the dash) in the same step. [guiding]: Android Auto's guidance is active now; with none, the time
     * without one counts from [nowMs] already.
     */
    fun hold(guiding: Boolean, nowMs: Long, send: () -> Unit) = synchronized(lock) {
        held = true
        inactiveSinceMs = if (guiding) null else nowMs
        send()
    }

    /** Runs [send] unless the hold is on; true when it ran. */
    fun sendUnlessHeld(send: () -> Unit): Boolean = synchronized(lock) {
        if (held) return@synchronized false
        send()
        true
    }

    /** Android Auto's guidance is inactive at [nowMs] (a status "inactive", a stop, the end of the session): the time without one starts counting. */
    fun inactive(nowMs: Long) = synchronized(lock) {
        if (held && inactiveSinceMs == null) inactiveSinceMs = nowMs
    }

    /** Android Auto's guidance is active at [nowMs]. True when it is a new one and ended the hold; a short gap (a recalculation) is forgotten and the hold stays. */
    fun active(nowMs: Long): Boolean = synchronized(lock) {
        if (!held) return@synchronized false
        val since = inactiveSinceMs
        inactiveSinceMs = null
        val fresh = since != null && nowMs - since >= newGuidanceAfterMs
        if (fresh) held = false
        fresh
    }

    /** The end of the link: the next one starts with nothing held. */
    fun reset() = synchronized(lock) {
        held = false
        inactiveSinceMs = null
    }

    companion object {
        /** Longer than the longest recalculation seen (7 s, [com.reau.app.aa.NavGuidance.INACTIVE_BY_STATUS]): an inactive guidance that lasts this long was ended, not recalculated. */
        const val NEW_GUIDANCE_AFTER_MS = 10_000L
    }
}
