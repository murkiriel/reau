package com.reau.app.service

import com.reau.app.weather.GpsReading

/**
 * What [GpsWatch] decides, with the system left out (the registrations and the clock come from outside, so the JVM tests can run it):
 * when to listen, when to stop and forget, and what a reading says. The GPS is listened to only while [want] is told it is wanted and
 * allowed; [stop] gives the registrations back, which is what turns the GPS off (a listener left registered keeps it on at 1 Hz until
 * the process dies).
 */
internal class GpsWatcher(private val clock: () -> Long) {
    @Volatile private var satellites: Satellites? = null
    @Volatile private var fix: Fix? = null
    @Volatile private var listening = false
    private var giveBack: (() -> Unit)? = null

    private class Satellites(val used: Int, val visible: Int, val atMs: Long)

    private class Fix(val accuracy: Float?, val bearing: Float?, val speed: Float?, val atMs: Long)

    /**
     * Starts listening when [wanted] and [permitted] says yes, and it is not listening yet: [start] makes the registrations and returns the
     * way to give them back, or null when it could not (the next ask tries again). Stops when it is not wanted or not allowed.
     */
    @Synchronized
    fun want(wanted: Boolean, permitted: () -> Boolean, start: () -> (() -> Unit)?) {
        if (!wanted || !permitted()) {
            stop()
        } else if (!listening) {
            giveBack = start() ?: return
            listening = true
        }
    }

    /** Gives the registrations back and forgets the satellites and the fix; nothing to do when it was not listening. */
    @Synchronized
    fun stop() {
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
