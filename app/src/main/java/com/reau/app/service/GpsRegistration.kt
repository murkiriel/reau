package com.reau.app.service

/** The two registrations the GPS card needs, as the system takes and gives them back. */
internal interface GpsRegistration {
    fun requestFixes()
    fun registerSatellites()
    fun removeFixes()
}

internal object GpsRegistrations {
    /**
     * Makes both registrations; true when both were taken. When the second one fails after the first was taken, the first is given back:
     * a location listener left registered keeps the GPS on at 1 Hz until the process dies, and [GpsWatch.stop] will not remove what
     * [GpsWatch.want] believes it never started.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // whatever the system refuses with, the answer is the same: not started
    fun start(registration: GpsRegistration): Boolean = try {
        registration.requestFixes()
        registration.registerSatellites()
        true
    } catch (e: Exception) {
        runCatching { registration.removeFixes() }
        false
    }
}
