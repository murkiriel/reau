package com.reau.app.service

/**
 * The ride log's lines of REau's lock mode (0.3.99 test build), with no Android in it, under the tag [ScreenLock.TAG] (`[lock]`): the window's brightness going down and coming
 * back, the rider's touches on the unlock track (a line each, at most one in [GROUP_MS], with the count so far), a drag let go short of the end, the drag that unlocked, Back
 * pressed in vain, REau losing the focus or the front, the proximity sensor, and the summary at the way out. States, numbers and times only. The state lines (the lock screen, the
 * display, the snapshot) are [LockLog]'s.
 */
internal object ScreenLockLog {
    /** At most one line of a kind (a touch on the track, Back) in this long; the line says how many there were so far, and the summary says the total. */
    const val GROUP_MS = 2_000L

    /** Why the window's brightness went back: the mode began, a touch on the track, a drag that went on, or the mode left. */
    const val BRIGHT_ENTER = "enter"
    const val BRIGHT_TOUCH = "touch"
    const val BRIGHT_DRAG = "drag"
    const val BRIGHT_EXIT = "exit"

    /** Why it went down: the time with no touch ran out. */
    const val DIM_IDLE = "idle"

    /** The brightness changing: `brightness dim reason=idle`, `brightness bright reason=touch`; [label] is `brightness` (the lock mode) or `lockui brightness` (the screen over the lock screen). */
    fun brightnessLine(label: String, dim: Boolean, reason: String) = "$label ${if (dim) "dim" else "bright"} reason=$reason"

    /**
     * Follows the window's brightness (dim or not) and says each CHANGE once, with the time spent dim. The first call always changes (the state before is not known), so
     * the way in is said (`bright reason=enter`).
     */
    class DimTrack(private val label: String = "brightness") {
        private var dim: Boolean? = null
        private var dimSinceMs = 0L
        private var dimTotalMs = 0L

        /** The brightness is asked to be [on] (dim) at [nowMs] for [reason] (null: [DIM_IDLE] going dim, [BRIGHT_EXIT] coming back); the line, or null when nothing changed. */
        fun set(on: Boolean, reason: String?, nowMs: Long): String? {
            if (dim == on) return null
            if (dim == true) dimTotalMs += (nowMs - dimSinceMs).coerceAtLeast(0L)
            dim = on
            if (on) dimSinceMs = nowMs
            return brightnessLine(label, on, reason ?: if (on) DIM_IDLE else BRIGHT_EXIT)
        }

        /** How long the brightness has been dim, up to [nowMs]. */
        fun dimMs(nowMs: Long): Long = dimTotalMs + if (dim == true) (nowMs - dimSinceMs).coerceAtLeast(0L) else 0L
    }

    /**
     * Counts events and says when a line is due: the first at once, then not before [minGapMs] has passed since the last said; the count it says is the total so far (what
     * was left out is in it). Said or not, every [hit] is counted.
     */
    class Grouper(private val minGapMs: Long = GROUP_MS) {
        var total = 0L
            private set
        private var lastSaidMs: Long? = null

        /** One more at [nowMs]; the total so far when a line is due, else null. */
        fun hit(nowMs: Long): Long? {
            total++
            val last = lastSaidMs
            if (last != null && nowMs - last in 0 until minGapMs) return null
            lastSaidMs = nowMs
            return total
        }
    }

    /** A touch on the track, [count] so far: `slide touch n=7`. */
    fun touchLine(count: Long) = "slide touch n=$count"

    /** A drag of the thumb let go short of the end, [percent] of the way at its farthest: `slide aborted at 47%`. */
    fun abortLine(percent: Int) = "slide aborted at $percent%"

    /** The drag that unlocked, [dragMs] from the finger down to the end: `slide done after 1.4s of drag`. */
    fun doneLine(dragMs: Long): String {
        val ms = dragMs.coerceAtLeast(0L)
        return "slide done after ${ms / MS_PER_S}.${ms % MS_PER_S / TENTH_MS}s of drag"
    }

    /** The share of the track, 0 to 1, as a whole percent. */
    fun percent(progress: Float): Int = (progress.coerceIn(0f, 1f) * PERCENT).toInt()

    /** Back pressed in the mode, [count] so far: the first says `back ignored`, the next ones (not closer than [GROUP_MS]) `back ignored n=5`. */
    fun backLine(count: Long) = if (count <= 1L) ScreenLock.BACK_LINE else "${ScreenLock.BACK_LINE} n=$count"

    /** The window lost or got back the focus while the mode is on (the notification shade, a system dialog, a call): `focus lost`, `focus regained after=3s`. */
    fun focusLine(hasFocus: Boolean, lostForMs: Long?): String =
        if (hasFocus) "focus regained" + (lostForMs?.let { " after=${it.coerceAtLeast(0L) / MS_PER_S}s" } ?: "") else "focus lost"

    /** The activity's own lifecycle while the mode is on. */
    const val PAUSED = "paused"
    const val RESUMED = "resumed"
    const val STOPPED = "stopped"

    /** `activity paused`, `activity resumed after=3s` ([awayMs] since it paused, when it did), `activity stopped`. */
    fun activityLine(event: String, awayMs: Long?): String =
        "activity $event" + (if (event == RESUMED && awayMs != null) " after=${awayMs.coerceAtLeast(0L) / MS_PER_S}s" else "")

    /** The proximity sensor changed while the lock is held: `sensor proximity=near value=0.0`; [count] changes so far when the line is not the first of a [Grouper]: `... n=7`. */
    fun sensorLine(near: Boolean, value: Float, count: Long = 1L) =
        "sensor proximity=${if (near) "near" else "far"} value=$value" + if (count > 1L) " n=$count" else ""

    /** The sensor listener was registered (said once per registration): `sensor proximity registered max=5.0`. */
    fun sensorRegisteredLine(maxRange: Float) = "sensor proximity registered max=$maxRange"

    /** The numbers of one lock mode, for the line it leaves with. Times in milliseconds are said in whole seconds. */
    data class Summary(
        val afterMs: Long,
        val dimMs: Long,
        val proximityOffMs: Long,
        val wakes: Long,
        val touches: Long,
        val aborted: Long,
        val backs: Long,
        val keyguardLocks: Long,
        val focusLost: Long,
    ) {
        /** `summary after=62s dim=41s proximity-off=0s wakes=1 slide-touches=7 slide-aborted=2 back-ignored=3 keyguard-locks=0 focus-lost=1` */
        fun line(): String =
            "summary after=${s(afterMs)}s dim=${s(dimMs)}s proximity-off=${s(proximityOffMs)}s wakes=$wakes slide-touches=$touches slide-aborted=$aborted " +
                "back-ignored=$backs keyguard-locks=$keyguardLocks focus-lost=$focusLost"

        private fun s(ms: Long) = ms.coerceAtLeast(0L) / MS_PER_S
    }

    /**
     * What one lock mode counts for its summary: the touches on the track (a [Grouper]), the drags let go short, the losses of the focus, the time the display
     * was off by the proximity lock, and where the keeper's wakes and the lock screen's comings up stood when it began (the summary says the difference).
     */
    class Counters(private val startMs: Long, private val wakesAtStart: Long, private val locksAtStart: Long) {
        val touches = Grouper()
        var aborted = 0L
            private set
        var focusLost = 0L
            private set
        private var focusLostAtMs: Long? = null
        private var pausedAtMs: Long? = null
        private var proximityOffSinceMs: Long? = null
        private var proximityOffTotalMs = 0L

        /** A drag was let go short of the end. */
        fun abort() {
            aborted++
        }

        /** The focus changed at [nowMs]; the line to log, or null for a change that is not one (gained while not lost). */
        fun focus(hasFocus: Boolean, nowMs: Long): String? {
            if (hasFocus) {
                val lostAt = focusLostAtMs ?: return null
                focusLostAtMs = null
                return focusLine(true, nowMs - lostAt)
            }
            if (focusLostAtMs != null) return null
            focusLostAtMs = nowMs
            focusLost++
            return focusLine(false, null)
        }

        /** The activity's [event] at [nowMs]: the line; `resumed` says how long it was away since `paused`. */
        fun activity(event: String, nowMs: Long): String {
            val away = pausedAtMs?.let { nowMs - it }
            when (event) {
                PAUSED -> pausedAtMs = nowMs
                RESUMED -> pausedAtMs = null
            }
            return activityLine(event, away)
        }

        /** The display went off by the proximity lock at [nowMs] (said again, it counts once). */
        fun proximityOff(nowMs: Long) {
            if (proximityOffSinceMs == null) proximityOffSinceMs = nowMs
        }

        /** The display is on again, or the mode left, at [nowMs]. */
        fun proximityOn(nowMs: Long) {
            val since = proximityOffSinceMs ?: return
            proximityOffSinceMs = null
            proximityOffTotalMs += (nowMs - since).coerceAtLeast(0L)
        }

        /** The summary at [nowMs], given the time dim ([DimTrack.dimMs]), the Back count of the mode's state and where the keeper's wakes and the lock screen's counts are now. */
        fun summary(nowMs: Long, dimMs: Long, backs: Long, wakes: Long, keyguardLocks: Long): Summary {
            proximityOn(nowMs)
            return Summary(
                afterMs = nowMs - startMs, dimMs = dimMs, proximityOffMs = proximityOffTotalMs, wakes = wakes - wakesAtStart, touches = touches.total,
                aborted = aborted, backs = backs, keyguardLocks = keyguardLocks - locksAtStart, focusLost = focusLost,
            )
        }
    }

    private const val MS_PER_S = 1_000L
    private const val TENTH_MS = 100L
    private const val PERCENT = 100
}
