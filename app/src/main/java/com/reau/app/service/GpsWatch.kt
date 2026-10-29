package com.reau.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.reau.app.weather.GpsReading

/**
 * The GPS for the dash's info ring: the satellites in the fix ([GnssStatus]) and the last fix (its error, heading and speed), listened to only
 * while the ring wants them (it asks with [want], and the switch, the card or the permission going away stops it). Needs the precise location
 * permission the app already declares and the rider's "Usar a localização" on; with the phone's screen off it also needs the foreground
 * service to carry the location type ([ForegroundTypes]), which it does when those two are given at the start. Nothing is stored or sent.
 */
internal object GpsWatch {
    private val watcher = GpsWatcher { SystemClock.elapsedRealtime() }

    private val locations = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            watcher.fixSeen(
                accuracy = location.takeIf { it.hasAccuracy() }?.accuracy,
                bearing = location.takeIf { it.hasBearing() }?.bearing,
                speed = location.takeIf { it.hasSpeed() }?.speed,
            )
        }

        // The callbacks older Android versions still call; the provider going away shows as satellites and fixes that age out.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            watcher.satellitesSeen(used, status.satelliteCount)
        }
    }

    /** Starts listening when [wanted] and the permission is there, stops when it is not; the same ask again does nothing. */
    fun want(context: Context, wanted: Boolean) {
        watcher.want(wanted, { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }) {
            start(context)
        }
    }

    /** The two registrations made, and the way to give them back; null when the system refused one (see [GpsRegistrations]). */
    @SuppressLint("MissingPermission") // the caller checked it
    private fun start(context: Context): (() -> Unit)? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val started = GpsRegistrations.start(object : GpsRegistration {
            override fun requestFixes() = lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, locations, Looper.getMainLooper())

            @Suppress("DEPRECATION") // the executor form comes with Android 11; the app supports 10
            override fun registerSatellites() {
                lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
            }

            override fun removeFixes() = lm.removeUpdates(locations)
        })
        if (!started) return null
        return {
            runCatching { lm.removeUpdates(locations) }
            runCatching { lm.unregisterGnssStatusCallback(gnss) }
        }
    }

    /** Stops listening and forgets what it heard: the GPS is off (the ring's switch, the card or the projection went). */
    fun stop() = watcher.stop()

    /** The GPS as it says now, or null while it is not listened to or has said nothing for [GpsWatcher.FRESH_MS]. */
    fun read(nowMs: Long = SystemClock.elapsedRealtime()): GpsReading? = watcher.read(nowMs)

    private const val UPDATE_MS = 1_000L
}
