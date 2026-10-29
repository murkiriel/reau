package com.reau.app.dash

/**
 * The dash was found in the Wi-Fi scan (by the lookup of its name, from the projection or from the screen's search). The scan
 * is read in several places and only one projection listens, so the news goes through here: the projection, if it has a dash
 * waiting out its retry, tries it at once instead of at the end of the wait (ride log of 20/10/2026: seen 11:47:38,
 * asked 11:47:45).
 */
internal object DashSighting {
    @Volatile var listener: (() -> Unit)? = null

    fun sighted() {
        listener?.invoke()
    }
}
