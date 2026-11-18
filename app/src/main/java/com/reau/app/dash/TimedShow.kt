package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * Something shown for a while and then taken away: the name of a joystick tap in the title of the dash's gold band ([ActionEcho]), the
 * joystick's legend on the dash's video ([ActionLegend]). One value at a time: a new [show] takes the place of the one showing and the
 * time starts again, so the last tap wins.
 *
 * Whoever shows it reads [current] when [changed] says something moved (at each [show], and when the time ran out or [clear] took it
 * away). The wait is [schedule]'s: it runs the action after the delay and gives back what cancels it (a coroutine's delay in the app, a
 * hand-made list in a test); the clock is a parameter too, so a test moves it by hand. Off ([enabled] false) nothing is kept or told, and
 * switching it off while something shows takes it away at once.
 */
class TimedShow<T : Any>(
    private val showMs: Long,
    private val nowMs: () -> Long = MonotonicClock::nowMs,
    private val schedule: (delayMs: Long, action: () -> Unit) -> (() -> Unit) = { _, _ -> {} },
    private val changed: () -> Unit = {},
) {
    private class Shown<T>(val value: T, val untilMs: Long)

    private val lock = Any()
    private var shown: Shown<T>? = null
    private var cancelWait: (() -> Unit)? = null

    /** Whether it is wanted (its switch in the settings). Written by the settings' side, read by the tap's thread. */
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) clear()
        }

    /** Keeps [value] for [showMs] from now, in the place of what showed, and tells [changed]; false (nothing kept) when it is off. */
    fun show(value: T): Boolean {
        val kept = synchronized(lock) {
            if (!enabled) return@synchronized false
            shown = Shown(value, nowMs() + showMs)
            cancelWait?.invoke()
            cancelWait = schedule(showMs, ::expire)
            true
        }
        if (kept) changed()
        return kept
    }

    /** What shows now, or null when nothing does or its time has run out. */
    fun current(): T? {
        val now = shown ?: return null
        return if (nowMs() < now.untilMs) now.value else null
    }

    /** How long what shows has still to show; 0 when nothing does. */
    fun remainingMs(): Long {
        val now = shown ?: return 0L
        return (now.untilMs - nowMs()).coerceAtLeast(0L)
    }

    /** Takes away what shows (and its wait), telling [changed] when something did. */
    fun clear() {
        val had = synchronized(lock) {
            val was = shown != null
            shown = null
            cancelWait?.invoke()
            cancelWait = null
            was
        }
        if (had) changed()
    }

    /** Takes away what shows and its wait without telling [changed]: for the end of the session, when there is nobody left to tell. */
    fun drop() {
        synchronized(lock) {
            shown = null
            cancelWait?.invoke()
            cancelWait = null
        }
    }

    /** The wait ran out. A wait that woke a little early (its delay is not this clock) waits again for what is left. */
    private fun expire() {
        val ended = synchronized(lock) {
            val now = shown
            when {
                now == null -> false
                now.untilMs - nowMs() > 0L -> {
                    cancelWait?.invoke()
                    cancelWait = schedule(now.untilMs - nowMs(), ::expire)
                    false
                }
                else -> {
                    shown = null
                    cancelWait = null
                    true
                }
            }
        }
        if (ended) changed()
    }
}
