package com.reau.app.service

import kotlin.math.hypot

/**
 * The rules of the "Bloquear a tela" button (the 0.3.93 test build), with no Android in it. The button opens REau's own dark screen in front, with the window keeping the
 * screen on at the minimum brightness, so the phone maker's task killer finds nothing to kill and Android never locks. It is not the system's lock: no PIN is asked or
 * switched off, no device administrator, no accessibility. The rider leaves it by dragging the round thumb to the right (the official app's way); the app leaving the
 * front, the screen going off (the display's state, since during a projection the SCREEN_OFF broadcast does not come) and the projection stopping end it too.
 * [com.reau.app.ui.ScreenLockMode] does the Android parts. The ride log lines are under the tag [TAG] (`[lock]`).
 */
internal object ScreenLock {
    const val TAG = "lock"

    /** The line when the mode begins. */
    const val ON_LINE = "manual on"

    /** The line when Back is pressed in the mode: it does nothing, and is said once per mode. */
    const val BACK_LINE = "back ignored"

    /** The thumb past this share of the track, when let go, leaves the mode; short of it, the thumb goes back to the start. */
    const val UNLOCK_AT = 0.9f

    /** Why the mode ended, for `manual off reason=...`. */
    enum class Exit(val logName: String) { SWIPE("swipe"), LEFT("left"), SCREENOFF("screenoff"), PROJECTION("projection") }

    /** The line when the mode ends: the reason and the seconds it lasted. */
    fun offLine(reason: Exit, afterSeconds: Long) = "manual off reason=${reason.logName} after=${afterSeconds}s"

    /** The button is there, and works, with the projection on. */
    fun available(projectionOn: Boolean): Boolean = projectionOn

    /** How far the thumb is along the track, 0 to 1: [dragPx] pixels of a [travelPx] long way (no way, no progress). */
    fun progress(dragPx: Float, travelPx: Float): Float = if (travelPx <= 0f) 0f else (dragPx / travelPx).coerceIn(0f, 1f)

    /** Whether letting go at [progress] leaves the mode: at [UNLOCK_AT] or past it. */
    fun unlocked(progress: Float): Boolean = progress >= UNLOCK_AT

    /** Whether a touch at ([x], [y]) is on the thumb, a circle of [radiusPx] around ([centerX], [centerY]) with [slopPx] to spare: only then does the touch drag it. */
    fun hitsThumb(x: Float, y: Float, centerX: Float, centerY: Float, radiusPx: Float, slopPx: Float = 0f): Boolean =
        hypot(x - centerX, y - centerY) <= radiusPx + slopPx

    /** `Display.STATE_ON`: any other state of the default display (off, doze, suspended) is the screen gone off. */
    const val DISPLAY_ON = 2

    /** The name of a `Display.STATE_*` value for the log; a number this does not know is `state<N>`. */
    fun displayName(state: Int): String = when (state) {
        0 -> "UNKNOWN"
        1 -> "OFF"
        DISPLAY_ON -> "ON"
        3 -> "DOZE"
        4 -> "DOZE_SUSPEND"
        5 -> "VR"
        6 -> "ON_SUSPEND"
        else -> "state$state"
    }

    /** A change of the display's state: the log line and whether the screen is no longer on. */
    class DisplayChange(val line: String, val off: Boolean)

    /** Follows the default display's state: says a change once, with its line, and nothing while the state stays the same. */
    class DisplayWatch(initial: Int = DISPLAY_ON) {
        private var last = initial

        fun offer(state: Int): DisplayChange? {
            if (state == last) return null
            last = state
            return DisplayChange("display state=${displayName(state)}", off = state != DISPLAY_ON)
        }
    }

    /** The mode's state: in or out, since when, and whether Back was already said. */
    class State {
        var active = false
            private set
        private var sinceMs = 0L
        private var backSaid = false

        /** Goes in at [nowMs]; the line to log, or null when it was already in. */
        fun enter(nowMs: Long): String? {
            if (active) return null
            active = true
            sinceMs = nowMs
            backSaid = false
            return ON_LINE
        }

        /** Goes out at [nowMs] for [reason]; the line to log, or null when it was not in. */
        fun exit(reason: Exit, nowMs: Long): String? {
            if (!active) return null
            active = false
            return offLine(reason, ((nowMs - sinceMs).coerceAtLeast(0L)) / MS_PER_S)
        }

        /** Back was pressed: [BACK_LINE] the first time in this mode, null after that (and null when the mode is not on). */
        fun backIgnored(): String? {
            if (!active || backSaid) return null
            backSaid = true
            return BACK_LINE
        }

        private companion object {
            const val MS_PER_S = 1_000L
        }
    }
}
