package com.reau.app.service

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The rules of the "Bloquear a tela" button (the 0.3.93 test build), with no Android in it. The button opens REau's own dark screen in front, with the window keeping the
 * screen on at the minimum brightness, so the phone maker's task killer finds nothing to kill and Android never locks. It is not the system's lock: no PIN is asked or
 * switched off, no device administrator, no accessibility. The rider leaves it by dragging the round thumb to the right; the app leaving the
 * front, the screen going off (the display's state, since during a projection the SCREEN_OFF broadcast does not come) and the projection stopping end it too.
 * [com.reau.app.ui.ScreenLockMode] does the Android parts. The ride log lines are under the tag [TAG] (`[lock]`).
 */
internal object ScreenLock {
    const val TAG = "lock"

    /** How the mode began: the rider pressed the button, or the screen was about to go off by itself and [IdleLock] took it in (the 0.3.97 test build). */
    enum class Entry(val logName: String) { BUTTON("button"), IDLE("idle") }

    /** The line when the mode begins, and how. */
    fun onLine(entry: Entry) = "manual on reason=${entry.logName}"

    /** The line when Back is pressed in the mode: it does nothing. The first is said at once, the next ones grouped with a count ([ScreenLockLog.backLine]). */
    const val BACK_LINE = "back ignored"

    /** The thumb past this share of the track, when let go, leaves the mode; short of it, the thumb goes back to the start. */
    const val UNLOCK_AT = 0.9f

    /** Why the mode ended, for `manual off reason=...`. */
    enum class Exit(val logName: String) { SWIPE("swipe"), LEFT("left"), SCREENOFF("screenoff"), PROJECTION("projection") }

    /** The line when the mode ends: the reason and the seconds it lasted. */
    fun offLine(reason: Exit, afterSeconds: Long) = "manual off reason=${reason.logName} after=${afterSeconds}s"

    /** The mode can begin, with the projection on (the button's own click checks it). */
    fun available(projectionOn: Boolean): Boolean = projectionOn

    /**
     * Whether the main screen shows the button: the switch "Modo de bloqueio do REau (teste)" is on, the projection runs and the dash's side of the bridge card says
     * "Pronto" ([DashLink.READY], which the card shows only once the projection is past its preparation). At "Conectando", "Preparando", stopped or an error the dash
     * is not receiving, and the button is not there. "Transmitindo" is the headline of the same moment (the dash ready and Android Auto's picture going to it), so it is covered.
     */
    fun buttonShown(switchOn: Boolean, phase: Phase, dash: DashLink): Boolean = switchOn && phase == Phase.RUNNING && dash == DashLink.READY

    /** How far the thumb is along the track, 0 to 1: [dragPx] pixels of a [travelPx] long way (no way, no progress). */
    fun progress(dragPx: Float, travelPx: Float): Float = if (travelPx <= 0f) 0f else (dragPx / travelPx).coerceIn(0f, 1f)

    /** Whether letting go at [progress] leaves the mode: at [UNLOCK_AT] or past it. */
    fun unlocked(progress: Float): Boolean = progress >= UNLOCK_AT

    /** Whether a touch at ([x], [y]) is on the thumb, a circle of [radiusPx] around ([centerX], [centerY]) with [slopPx] to spare: only then does the touch drag it. */
    @Suppress("LongParameterList") // the touch, the circle's centre and radius, and the slop: six plain numbers of one geometry
    fun hitsThumb(x: Float, y: Float, centerX: Float, centerY: Float, radiusPx: Float, slopPx: Float = 0f): Boolean =
        hypot(x - centerX, y - centerY) <= radiusPx + slopPx

    /**
     * The brightness of the lock screen: down to the minimum [dimAfterMs] after the screen began and after the last touch ON THE TRACK (the narrow strip the thumb runs on,
     * anywhere along it). A touch outside the track does not bring it back (the screen swallows those touches, they say nothing). Times are of one clock, in ms.
     */
    class BrightnessRule(private val dimAfterMs: Long, startMs: Long) {
        companion object {
            /** A move of the thumb refreshes the brightness at most this often. */
            const val MOVE_REFRESH_MS = 1_000L
        }

        private var brightSince = startMs

        /** A touch at [nowMs]; true when it was on the track, so the brightness comes back now and the count to the minimum begins again. */
        fun touch(nowMs: Long, onTrack: Boolean): Boolean {
            if (onTrack) brightSince = nowMs
            return onTrack
        }

        /**
         * The thumb moved at [nowMs] with the finger down: the count to the minimum begins again, so a drag that lasts longer than [dimAfterMs] is made in the normal
         * brightness. True when the brightness is to be refreshed now: at most once in [MOVE_REFRESH_MS] (a move comes every frame, and each refresh is a pass over the window).
         */
        fun move(nowMs: Long): Boolean {
            if (nowMs - brightSince < MOVE_REFRESH_MS) return false
            brightSince = nowMs
            return true
        }

        /** Whether the brightness is at the minimum at [nowMs]. */
        fun dimmed(nowMs: Long): Boolean = nowMs - brightSince >= dimAfterMs

        /** How long until the minimum, from [nowMs]; 0 when it is there already. */
        fun msUntilDim(nowMs: Long): Long = (dimAfterMs - (nowMs - brightSince)).coerceAtLeast(0L)
    }

    /** The card's battery line: `64 % · 37 °C`; a part that is not known is `-` (and `-` alone when neither is). */
    fun batteryText(percent: Int?, tempC: Float?): String =
        if (percent == null && tempC == null) "-" else "${percent?.let { "$it %" } ?: "-"} · ${tempC?.let { "${it.roundToInt()} °C" } ?: "-"}"

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

    /** A change of the display's state: whether the screen is no longer on (the line of the change is [LockLog.displayLine], written by [ScreenWatch]). */
    class DisplayChange(val off: Boolean)

    /** Follows the default display's state: says a change once, and nothing while the state stays the same. */
    class DisplayWatch(initial: Int = DISPLAY_ON) {
        private var last = initial

        fun offer(state: Int): DisplayChange? {
            if (state == last) return null
            last = state
            return DisplayChange(off = state != DISPLAY_ON)
        }
    }

    /** The line of the proximity lock (0.3.99) taken or let go, and why: `proximity lock on (enter)`, `proximity lock off (swipe)`. */
    fun proximityLine(on: Boolean, reason: String) = "proximity lock ${if (on) "on" else "off"} ($reason)"

    /** Said once when the phone has no proximity wake lock: the mode goes on exactly as it did before 0.3.99. */
    const val PROXIMITY_UNSUPPORTED_LINE = "proximity lock not supported"

    /** The reasons the lock is taken: the mode went in, or the app came back to the front with the mode still on. */
    const val PROXIMITY_ENTER = "enter"
    const val PROXIMITY_RESUME = "resume"

    /** The reasons the lock is let go besides the mode's own [Exit]s: the app left the front without the mode ending (onPause), or the activity was destroyed. */
    const val PROXIMITY_PAUSE = "paused"
    const val PROXIMITY_DESTROY = "destroyed"

    /**
     * Says the display going off and coming back because of the proximity lock, once per change (the same off is heard by the listener and the check after it).
     * [off] is given what [com.reau.app.service.ScreenKeepPolicy.proximityCovers] said for a display that is not on.
     */
    class ProximityEdge {
        private var byProximity = false

        /** The display went off, [covered] says by the lock, with the sensor reading [sensor]; the line, or null when it is not the lock's or was already said. */
        fun off(covered: Boolean, sensor: String): String? {
            if (!covered || byProximity) return null
            byProximity = true
            return "display off by proximity sensor=$sensor"
        }

        /** The display came back on: the line when it had gone off by the lock, else null. */
        fun on(): String? {
            if (!byProximity) return null
            byProximity = false
            return "display on again (proximity uncovered)"
        }
    }

    /** The mode's state: in or out, since when, and how many times Back was pressed (said grouped, [ScreenLockLog.Grouper]). */
    class State {
        var active = false
            private set
        private var sinceMs = 0L
        private var backs = ScreenLockLog.Grouper()

        /** How many times Back was pressed in this mode. */
        val backCount: Long get() = backs.total

        /** Goes in at [nowMs], by [entry]; the line to log, or null when it was already in. */
        fun enter(nowMs: Long, entry: Entry = Entry.BUTTON): String? {
            if (active) return null
            active = true
            sinceMs = nowMs
            backs = ScreenLockLog.Grouper()
            return onLine(entry)
        }

        /** Goes out at [nowMs] for [reason]; the line to log, or null when it was not in. */
        fun exit(reason: Exit, nowMs: Long): String? {
            if (!active) return null
            active = false
            return offLine(reason, ((nowMs - sinceMs).coerceAtLeast(0L)) / MS_PER_S)
        }

        /** Back was pressed at [nowMs]: [BACK_LINE] the first time in this mode, then a line with the count at most once in [ScreenLockLog.GROUP_MS], else null (and null when the mode is not on). */
        fun backIgnored(nowMs: Long): String? {
            if (!active) return null
            return backs.hit(nowMs)?.let(ScreenLockLog::backLine)
        }

        private companion object {
            const val MS_PER_S = 1_000L
        }
    }
}
