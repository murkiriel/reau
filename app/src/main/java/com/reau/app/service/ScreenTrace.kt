package com.reau.app.service

/**
 * What decides, with no Android in it, which of the phone's states are said (0.3.99 test build, [LockLog] has the lines): the display's state and the lock screen, each only when
 * it CHANGES, with the cause of a lock deduced from what was heard before it. Shared by every source that listens ([ScreenWatch] feeds it); it lives while at least one source is
 * begun ([begin]) (the keeper, the lock mode). The first reading after [begin] only seeds the state (the snapshot says where things began). Main thread only.
 */
internal class ScreenTrace(private val log: (String) -> Unit) {
    private val sources = linkedSetOf<String>()
    private var epochMs = 0L
    private var display: Int? = null
    private var locked: Boolean? = null
    private var screenOffAtMs: Long? = null
    private var userPresentAtMs: Long? = null
    private var dismissedAtMs: Long? = null

    /** How many times the keeper lit the screen since the first source began (the lock mode's summary takes the difference). */
    var wakes = 0L
        private set

    /** How many times the lock screen came up since the first source began. */
    var keyguardLocks = 0L
        private set

    /** Whether any source is on now. */
    val active: Boolean get() = sources.isNotEmpty()

    /** Whether [source] is on now. */
    fun has(source: String): Boolean = source in sources

    /** [source] begins at [nowMs]; true when it is the first (the clock of `at=` and the seeds start now). */
    fun begin(source: String, nowMs: Long): Boolean {
        val first = sources.isEmpty()
        sources += source
        if (first) {
            epochMs = nowMs
            display = null
            locked = null
            screenOffAtMs = null
            userPresentAtMs = null
            dismissedAtMs = null
        }
        return first
    }

    /** [source] ends; true when it was the last. */
    fun end(source: String): Boolean = sources.remove(source) && sources.isEmpty()

    /** Seconds since the first source began, for `at=`. */
    fun seconds(nowMs: Long): Long = (nowMs - epochMs).coerceAtLeast(0L) / MS_PER_S

    /** The SCREEN_OFF broadcast came at [nowMs]. */
    fun screenOff(nowMs: Long) {
        screenOffAtMs = nowMs
    }

    /** ACTION_USER_PRESENT came at [nowMs] (the rider unlocked the phone). */
    fun userPresent(nowMs: Long) {
        userPresentAtMs = nowMs
    }

    /** Android said a dismiss of the lock screen succeeded at [nowMs]. */
    fun dismissed(nowMs: Long) {
        dismissedAtMs = nowMs
    }

    /** The keeper lit the screen. */
    fun wake() {
        wakes++
    }

    /** The phone as it is at [nowMs]: a change of the display's state or of the lock screen since the last look is written (the first look only remembers). */
    fun observe(reading: LockLog.Reading, nowMs: Long) {
        if (!active) return
        reading.displayState?.let { state ->
            val before = display
            display = state
            if (before != null && before != state) {
                log(LockLog.displayLine(state, reading.interactive))
                if (ScreenKeepPolicy.displayOff(state) && !ScreenKeepPolicy.displayOff(before)) screenOffAtMs = nowMs
            }
        }
        reading.keyguardLocked?.let { now ->
            val before = locked
            locked = now
            if (before != null && before != now) {
                if (now) keyguardLocks++
                val cause = LockLog.keyguardCause(now, reading, since(screenOffAtMs, nowMs), since(userPresentAtMs, nowMs), since(dismissedAtMs, nowMs))
                log(LockLog.keyguardLine(now, seconds(nowMs), reading, cause))
            }
        }
    }

    private fun since(atMs: Long?, nowMs: Long): Long? = atMs?.let { nowMs - it }

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
