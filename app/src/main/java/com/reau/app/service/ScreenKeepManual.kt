package com.reau.app.service

/**
 * The rules of the "Bloquear a tela" button (the 0.3.92 test build), with no Android in it: the manual mode that shows REau's own dark screen in front, with the screen
 * kept lit at the minimum, so the maker's task killer finds nothing to kill. Not the system's lock and no device administrator: the rider leaves it by a swipe up, by
 * Back, or when the projection stops; the power button turns the screen off and ends it too. [com.reau.app.ui.ManualLock] does the Android parts.
 */
internal object ScreenKeepManual {
    /** Why the manual mode ended, for the ride log's `manual off reason=...`. */
    enum class Exit(val logName: String) { SWIPE("swipe"), BACK("back"), PROJECTION("projection"), SCREEN_OFF("screenoff") }

    /** The ride log's line when the mode begins (under the tag [ScreenKeepPolicy.TAG]). */
    const val ON_LINE = "manual on"

    /** A drag up of at least this many dp, from anywhere on the screen, leaves the mode; a shorter one (a loose touch) does nothing. */
    const val SWIPE_UP_DP = 96

    /** The ride log's line when it ends: the reason and the seconds it lasted. */
    fun offLine(reason: Exit, afterSeconds: Long) = "manual off reason=${reason.logName} after=${afterSeconds}s"

    /** Whether a vertical drag that moved [totalDy] pixels (negative: up) is a swipe up of [thresholdPx] or more. */
    fun swipedUp(totalDy: Float, thresholdPx: Float): Boolean = totalDy <= -thresholdPx

    /** Whether the button is shown and works: with the projection on, whatever the switch "Tela acesa com o celular bloqueado" says. */
    fun available(projectionOn: Boolean): Boolean = projectionOn

    /** The mode's state: in or out, and since when, so that each entry and each exit says its line once. */
    class State {
        var active = false
            private set
        private var sinceMs = 0L

        /** Goes in at [nowMs]; the line to log, or null when it was already in. */
        fun enter(nowMs: Long): String? {
            if (active) return null
            active = true
            sinceMs = nowMs
            return ON_LINE
        }

        /** Goes out at [nowMs] for [reason]; the line to log, or null when it was not in. */
        fun exit(reason: Exit, nowMs: Long): String? {
            if (!active) return null
            active = false
            return offLine(reason, ((nowMs - sinceMs).coerceAtLeast(0L)) / MS_PER_S)
        }

        private companion object {
            const val MS_PER_S = 1_000L
        }
    }
}
