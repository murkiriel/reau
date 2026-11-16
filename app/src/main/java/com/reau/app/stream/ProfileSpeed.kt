package com.reau.app.stream

import java.util.Locale

/**
 * The speed the speed profile ([SpeedProfile]) reads. The route's estimate ([NavSpeed]) comes from how fast the distance to the next maneuver goes
 * down, in steps of 100 m, and erred 11 km/h on average against the bubble of the navigation app. The experimental switch gives it the speed of the
 * last GPS fix instead ([com.reau.app.service.GpsWatch.read]) when there is one: a route runs, and the GPS is listened to (the switch itself asks for
 * it during a route, [com.reau.app.service.SpeedGps], and the dash's info ring may ask too) with a fix of the last 10 s that carries a speed.
 * Otherwise, and with the switch off, it is the route's estimate, as always. Each change of source is said in the ride log (no place, only the
 * speeds), and so is the switch being on with the GPS asked and no fix yet.
 */
class ProfileSpeed(private val log: (String) -> Unit = {}) {
    private var onGps: Boolean? = null
    private var noFixSaid = false

    /**
     * km/h for the profile. [on] is the switch, [routeActive] whether a route runs, [gpsMps] the GPS fix's speed in metres per second (null: the
     * GPS says none) and [routeKmh] the route's estimate (null: it has none). [gpsAsked] is whether the switch has the GPS asked now: with it and
     * no fix, the log says so once per change ([NO_FIX_LINE]); the line of the way back to the route's estimate already says it for a fix that went.
     */
    @Synchronized
    fun kmh(on: Boolean, routeActive: Boolean, gpsMps: Float?, routeKmh: Double?, gpsAsked: Boolean = false): Double? {
        if (!on) {
            onGps = null
            noFixSaid = false
            return routeKmh
        }
        val gps = gpsMps?.takeIf { routeActive && it.isFinite() && it >= 0f }?.let { it * MPS_TO_KMH }
        val now = gps != null
        if (now != onGps && (now || onGps != null)) sayChange(gps, routeKmh)
        sayNoFix(waiting = gpsAsked && gps == null)
        onGps = now
        return gps ?: routeKmh
    }

    private fun sayChange(gps: Double?, routeKmh: Double?) {
        val route = routeKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "none"
        log(
            if (gps != null) {
                "speed profile: reads the GPS speed (%.0f km/h; the route says %s)".format(Locale.US, gps, route)
            } else {
                "speed profile: back to the route's estimate (no GPS speed, or no route)"
            },
        )
    }

    /** Once per change: not for a fix that went (the line of the way back said it), and not again while it goes on. */
    private fun sayNoFix(waiting: Boolean) {
        if (waiting && !noFixSaid && onGps != true) log(NO_FIX_LINE)
        noFixSaid = waiting
    }

    companion object {
        /** Said once when the switch has the GPS asked and it has no fresh fix yet; the speed is the route's meanwhile. */
        const val NO_FIX_LINE = "speed profile: GPS key on, no GPS fix yet; route speed used"
        private const val MPS_TO_KMH = 3.6
    }
}
