package com.reau.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * Android's own Location switch, which the rider may turn off while REau has the permission: the phone then does not return the Wi-Fi
 * scan, so REau cannot see the dash in the scan (it asks again when the dash shows up) and the GPS card and the sun theme have no place.
 * Another app for this dash (NorthStar) found the first authentication failing on Android 13 with the switch off.
 */
object LocationSwitch {
    /** The warning is for a rider who gave the permission and then switched Location off; without the permission the permissions screen speaks. */
    fun offDespitePermission(granted: Boolean, switchOn: Boolean): Boolean = granted && !switchOn

    fun read(context: Context): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return offDespitePermission(granted, manager.isLocationEnabled)
    }
}
