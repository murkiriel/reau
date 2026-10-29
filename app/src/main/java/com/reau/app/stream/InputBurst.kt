package com.reau.app.stream

/**
 * Whether the rider's last input to Android Auto is still fresh at a time. Pure, so the JVM tests cover it; [InputBurst] is the one the
 * app feeds. The clock is the caller's (the same one on both sides of a call).
 */
internal class FreshInput(private val freshMs: Long = FRESH_MS) {
    private var lastMs = NEVER

    fun sent(nowMs: Long) {
        lastMs = nowMs
    }

    fun fresh(nowMs: Long): Boolean = lastMs != NEVER && nowMs - lastMs in 0..freshMs

    companion object {
        /** Long enough for Android Auto to answer a key (25 to 290 ms in the logs of 21/10/2026) and for the menu's fit to follow it (about 1 s). */
        const val FRESH_MS = 1_800L
        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * The compositor draws a new picture of Android Auto at once while the rider's input is fresh, instead of at the next tick: at the
 * profile's 2 fps a joystick touch outside the menu waited 300 to 600 ms for the tick (`drawn +…` in the `joystick:` lines of 21/10/2026,
 * with Android Auto itself answering in about 100 ms). Time is [android.os.SystemClock.uptimeMillis].
 */
internal object InputBurst {
    private val input = FreshInput()

    // The screen changes a second or two after an input (the app picked in the menu takes that long to draw): the picture is looked at more often then.
    private val lookInput = FreshInput(LOOK_QUICK_WINDOW_MS)

    /** The least time between two frames drawn this way: about 12 a second at most, so a long scroll cannot flood the encoder. */
    const val GAP_MS = 80L

    @Synchronized fun sent(nowMs: Long) {
        input.sent(nowMs)
        lookInput.sent(nowMs)
    }

    /** How long after an input the picture is looked at at the quick pace. */
    const val LOOK_QUICK_WINDOW_MS = 3_000L

    /** The time between two looks at the picture: [LOOK_QUICK_MS] while the rider's input is [lookQuick], else [LOOK_CALM_MS]. */
    fun lookEveryMs(lookQuick: Boolean): Long = if (lookQuick) LOOK_QUICK_MS else LOOK_CALM_MS

    const val LOOK_CALM_MS = 250L
    const val LOOK_QUICK_MS = 100L

    @Synchronized fun lookQuick(nowMs: Long): Boolean = lookInput.fresh(nowMs)

    @Synchronized fun fresh(nowMs: Long): Boolean = input.fresh(nowMs)
}
