package com.reau.app.dash

/**
 * The dash was found in the Wi-Fi scan (by the lookup of its name, from the projection or from the screen's search). The scan
 * is read in several places and only one projection listens, so the news goes through here: the projection, if it has a dash
 * waiting out its retry, tries it at once instead of at the end of the wait (ride log of 20/10/2026: seen 11:47:38,
 * asked 11:47:45).
 */
internal object DashSighting {
    private val lock = Any()
    @Volatile private var current: (() -> Unit)? = null

    var listener: (() -> Unit)?
        get() = current
        set(value) {
            synchronized(lock) { current = value }
        }

    /**
     * Gives the listener back, but only if it is still [mine]. A projection that is going away does this late, on the thread of its own, and the next
     * one may have taken the listener by then: clearing it blindly left the new projection deaf to the sighting (A-20 of the independent review of 26/10/2026).
     */
    fun release(mine: () -> Unit) {
        synchronized(lock) { if (current === mine) current = null }
    }

    fun sighted() {
        listener?.invoke()
    }
}
