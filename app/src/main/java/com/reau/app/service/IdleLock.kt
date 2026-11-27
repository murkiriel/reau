package com.reau.app.service

/**
 * The idle lock of the 0.3.97 test build, with no Android in it: with the "Modo de bloqueio do REau (teste)" on and the dash ready (the button's own condition), REau does not let
 * the phone's screen go off by itself. While REau is in front it keeps the screen on and counts the phone's own screen timeout from the last touch; when the count runs out it takes
 * the screen into REau's lock ([ScreenLock], the same as the button) instead of letting Android turn it off (and lock it, and ask for the PIN). [com.reau.app.ui.IdleLockWatch]
 * does the Android parts. The ride log lines are under the tag `lock`.
 */
internal object IdleLock {
    /** The shortest and the longest the count may be, for a system value that is odd (a few seconds, or "never"). */
    const val MIN_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 10 * 60_000L

    /** What the system says when it says nothing: Android's own default screen timeout. */
    const val DEFAULT_SYSTEM_MS = 30_000

    /**
     * The count's length for the system's screen timeout [systemMs] (`Settings.System.SCREEN_OFF_TIMEOUT`, ms): that value, not under [MIN_TIMEOUT_MS] and not over [MAX_TIMEOUT_MS];
     * a value that is zero or negative ("never", on some phones) takes the longest.
     */
    fun timeoutMs(systemMs: Int): Long = if (systemMs <= 0) MAX_TIMEOUT_MS else systemMs.toLong().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** Whether the idle count runs: the switch on, the projection running, the dash ready, REau resumed (in front) and REau's lock not yet on. */
    fun active(switchOn: Boolean, phase: Phase, dash: DashLink, resumed: Boolean, lockActive: Boolean): Boolean =
        offReason(switchOn, phase, dash, resumed, lockActive) == null

    /** The first reason the count does not run, for `idle timer off reason=...`; null when it runs. */
    fun offReason(switchOn: Boolean, phase: Phase, dash: DashLink, resumed: Boolean, lockActive: Boolean): String? = when {
        !switchOn -> "switch"
        phase != Phase.RUNNING -> "projection"
        dash != DashLink.READY -> "dash"
        !resumed -> "left"
        lockActive -> "locked"
        else -> null
    }

    /** The line when the count begins. */
    fun onLine(timeoutMs: Long) = "idle timer on timeout=${timeoutMs / MS_PER_S}s"

    /** The line when it stops, and why. */
    fun offLine(reason: String) = "idle timer off reason=$reason"

    /** The count: it begins at [startMs] and a touch restarts it; it runs out [timeoutMs] after the last one. */
    class Count(private val timeoutMs: Long, startMs: Long) {
        private var lastTouchMs = startMs

        /** The rider touched the window at [nowMs]: the count begins again. */
        fun touch(nowMs: Long) {
            lastTouchMs = nowMs
        }

        /** How long until it runs out, from [nowMs]; 0 when it has. */
        fun msUntilExpiry(nowMs: Long): Long = (timeoutMs - (nowMs - lastTouchMs)).coerceAtLeast(0L)

        fun expired(nowMs: Long): Boolean = msUntilExpiry(nowMs) == 0L
    }

    /**
     * The window has one "keep the screen on" flag and two things want it: this idle count and the lock mode ([ScreenLock]). The flag is on while any of them wants it and
     * off when the last lets go, so neither takes the other's away. Each call says whether the flag is to be set or cleared NOW.
     */
    class KeepSources {
        private val wanting = mutableSetOf<String>()

        /** [source] wants the flag; true when the flag is to be set now (it was the first). */
        fun want(source: String): Boolean {
            val first = wanting.isEmpty()
            wanting += source
            return first
        }

        /** [source] lets go; true when the flag is to be cleared now (it was the last). */
        fun release(source: String): Boolean = wanting.remove(source) && wanting.isEmpty()
    }

    private const val MS_PER_S = 1_000L
}
