package com.reau.app.service

import android.net.wifi.WifiManager

/**
 * The phone's Wi-Fi radio going on or off, from [SystemEvents] (which hears Android's broadcast for as long as the app lives) to the
 * projection that is running (which listens only while it does). One listener at a time, like [com.reau.app.dash.DashSighting]: the projection's
 * machine learns of the radio coming on and tries the dash at once, instead of at the end of its wait.
 */
internal object WifiRadio {
    private val lock = Any()
    @Volatile private var current: ((Boolean) -> Unit)? = null

    var listener: ((Boolean) -> Unit)?
        get() = current
        set(value) {
            synchronized(lock) { current = value }
        }

    /** Gives the listener back, but only if it is still [mine]: a projection going away late does not take the next one's. */
    fun release(mine: (Boolean) -> Unit) {
        synchronized(lock) { if (current === mine) current = null }
    }

    /** The radio is now [on] (null: a state that says nothing yet, such as "enabling"). */
    fun heard(on: Boolean?) {
        if (on != null) listener?.invoke(on)
    }

    /**
     * What a `wifi_state` of [WifiManager.WIFI_STATE_CHANGED_ACTION] says: on only when enabled, off from the moment it starts going down, and nothing
     * while it is coming up or unknown (the enabled that follows says it).
     */
    fun of(state: Int): Boolean? = when (state) {
        WifiManager.WIFI_STATE_ENABLED -> true
        WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> false
        else -> null
    }
}
