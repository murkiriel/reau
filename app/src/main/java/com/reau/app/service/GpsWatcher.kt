package com.reau.app.service

import com.reau.app.weather.GpsReading

/** Who asks for the GPS: the info ring's GPS card (a second owner, the experimental speed profile, was taken out on 20/11/2026). The GPS stays on while any owner asks. */
internal enum class GpsOwner { RING }

/**
 * What [GpsWatch] decides, with the system left out (the registrations and the clock come from outside, so the JVM tests can run it):
 * when to listen, when to stop and forget, and what a reading says. The GPS is listened to only while some [GpsOwner] is told by [want] that
 * it is wanted and allowed: one owner letting go ([release]) keeps it on for the other, and it goes off when the last one lets go. [stop]
 * lets every owner go (the end of the projection). Giving the registrations back is what turns the GPS off (a listener left registered
 * keeps it on at 1 Hz until the process dies).
 */
internal class GpsWatcher(private val clock: () -> Long) {
    @Volatile private var satellites: Satellites? = null
    @Volatile private var fix: Fix? = null
    @Volatile private var listening = false
    private val owners = mutableSetOf<GpsOwner>()
    private var giveBack: (() -> Unit)? = null

    private class Satellites(val used: Int, val visible: Int, val atMs: Long)

    private class Fix(val accuracy: Float?, val bearing: Float?, val speed: Float?, val atMs: Long)

    /**
     * What [owner] asks: it is in while [wanted] and [permitted] says yes, and out otherwise. Starts listening when some owner is in and it is
     * not listening yet: [start] makes the registrations and returns the way to give them back, or null when it could not (the next ask
     * tries again). Stops when no owner is in.
     */
    @Synchronized
    fun want(owner: GpsOwner, wanted: Boolean, permitted: () -> Boolean, start: () -> (() -> Unit)?) {
        if (!wanted || !permitted()) {
            release(owner)
        } else {
            owners += owner
            if (!listening) {
                giveBack = start() ?: return
                listening = true
            }
        }
    }

    /** The info ring's ask (the first owner; what the tests of the ring's GPS say). */
    fun want(wanted: Boolean, permitted: () -> Boolean, start: () -> (() -> Unit)?) = want(GpsOwner.RING, wanted, permitted, start)

    /** [owner] lets go; the GPS stays on while another owner still asks, and goes off with the last. */
    @Synchronized
    fun release(owner: GpsOwner) {
        owners -= owner
        if (owners.isEmpty()) stop()
    }

    /** Whether [owner] is asking for the GPS now. */
    @Synchronized
    fun asking(owner: GpsOwner): Boolean = owner in owners

    /** Every owner lets go: gives the registrations back and forgets the satellites and the fix; nothing to do when it was not listening. */
    @Synchronized
    fun stop() {
        owners.clear()
        if (!listening) return
        runCatching { giveBack?.invoke() }
        giveBack = null
        listening = false
        satellites = null
        fix = null
    }

    fun satellitesSeen(used: Int, visible: Int) {
        satellites = Satellites(used, visible, clock())
    }

    fun fixSeen(accuracy: Float?, bearing: Float?, speed: Float?) {
        fix = Fix(accuracy, bearing, speed, clock())
    }

    /** The GPS as it says now, or null while it is not listened to or has said nothing for [FRESH_MS]. */
    fun read(nowMs: Long = clock()): GpsReading? {
        if (!listening) return null
        val sky = satellites?.takeIf { nowMs - it.atMs <= FRESH_MS } ?: return null
        val last = fix?.takeIf { nowMs - it.atMs <= FRESH_MS }
        return GpsReading(sky.used, sky.visible, last?.accuracy, last?.bearing, last?.speed)
    }

    companion object {
        /** A fix older than this says nothing of where the phone moves; satellites older than it say nothing of the sky. */
        const val FRESH_MS = 10_000L
    }
}
