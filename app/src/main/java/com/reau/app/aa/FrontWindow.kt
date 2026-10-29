package com.reau.app.aa

/**
 * Which app is in front of Android Auto's settings while the server's walk runs ([ServerMenuRun]): the window in front on the PHONE'S OWN
 * screen. The system's "active window" is not that: on 23/10/2026 a session that started with Google Maps in front made the active window
 * Maps' projection window, on one of Android Auto's virtual displays, and the walk took it for another app in front of the settings: it
 * ended at once, with no "back" and no REau, and Android Auto's settings stayed in front of REau. The service's list of windows holds
 * only the phone's screen, so the window is taken from it: the active one, else the focused one; none says nothing (null: not "elsewhere").
 */
object FrontWindow {
    /** One window of the phone's screen (never the cover, which is this service's own). */
    data class Win(val packageName: String?, val active: Boolean, val focused: Boolean)

    /**
     * The package in front among [onPhone], the windows of the phone's screen. With no window listed at all (a screen whose windows the
     * system does not give) [fallbackActive], the active window's package, stands in: what the walk read before.
     */
    fun of(onPhone: List<Win>, fallbackActive: String?): String? =
        if (onPhone.isEmpty()) fallbackActive else (onPhone.firstOrNull { it.active } ?: onPhone.firstOrNull { it.focused })?.packageName
}
