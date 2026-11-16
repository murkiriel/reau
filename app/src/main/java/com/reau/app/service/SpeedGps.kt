package com.reau.app.service

/**
 * Whether the experimental "GPS speed for the profiles" switch wants the GPS now: only with all five true. The switch, the rider's
 * "Usar a localização", the speed profile ([speedFpsProfile]), a route that runs and the precise location permission.
 */
internal fun speedGpsWanted(
    experimentGpsSpeed: Boolean,
    useLocation: Boolean,
    speedFpsProfile: Boolean,
    routeActive: Boolean,
    permitted: Boolean,
): Boolean = experimentGpsSpeed && useLocation && speedFpsProfile && routeActive && permitted

/**
 * The speed profile's ask for the GPS ([GpsOwner.SPEED]), with the system left out ([ask] gives the ask to the GPS, [log] writes the ride
 * log), so the JVM tests can run it. It is looked at as often as the profile reads its speed ([step]); the ask goes on while [speedGpsWanted]
 * is true, and for [HOLD_MS] after the route ends, so a route that flickers (inactive and active again within seconds) does not switch the GPS
 * on and off at each sample. The other conditions letting go (the switch, the location, the profile or the permission) are not held.
 * [stop] is the end of the projection (for good: a later [step] asks for nothing). The ride log says each time the ask starts and each time it ends, and why.
 */
internal class SpeedGps(private val ask: (Boolean) -> Unit, private val log: (String) -> Unit) {
    private var asking = false
    private var routeSeenMs = NONE
    private var stopped = false

    /** Whether the ask is in force now, the retention included. */
    val isAsking: Boolean
        @Synchronized get() = asking

    /** One look at [nowMs]; true when the GPS is asked for the speed profile after it. */
    @Synchronized
    @Suppress("LongParameterList") // the five conditions of speedGpsWanted and the clock, each its own fact
    fun step(nowMs: Long, key: Boolean, useLocation: Boolean, speedProfile: Boolean, routeActive: Boolean, permitted: Boolean): Boolean {
        if (stopped) return false // a look that comes after the end of the projection must not ask for the GPS again
        val others = key && useLocation && speedProfile && permitted
        if (!others) routeSeenMs = NONE
        val live = speedGpsWanted(key, useLocation, speedProfile, routeActive, permitted)
        if (live) routeSeenMs = nowMs
        val held = !live && others && routeSeenMs != NONE && nowMs - routeSeenMs < HOLD_MS
        val wanted = live || held
        if (wanted) {
            if (!asking) log("gps: on for the speed profile (route)")
            asking = true
            ask(true) // every look: a start the system refused is tried again, as the ring's ask does
        } else if (asking) {
            asking = false
            routeSeenMs = NONE
            ask(false)
            log("gps: off for the speed profile (${reasonOff(key, useLocation, speedProfile, permitted)})")
        }
        return wanted
    }

    /** The projection stopped: the ask ends, with the line if it was in force. */
    @Synchronized
    fun stop() {
        stopped = true
        routeSeenMs = NONE
        if (!asking) return
        asking = false
        ask(false)
        log("gps: off for the speed profile (projection stopped)")
    }

    private fun reasonOff(key: Boolean, useLocation: Boolean, speedProfile: Boolean, permitted: Boolean): String = when {
        !key -> "key off"
        !useLocation -> "location off"
        !speedProfile -> "speed profile off"
        !permitted -> "no location permission"
        else -> "route ended"
    }

    companion object {
        /** How long the ask stays after the route ends. */
        const val HOLD_MS = 10_000L
        private const val NONE = Long.MIN_VALUE
    }
}
