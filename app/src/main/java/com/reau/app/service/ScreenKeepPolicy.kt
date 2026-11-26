package com.reau.app.service

import com.reau.app.settings.ScreenKeepMode

/**
 * The decision of "Tela acesa com o celular bloqueado", with no Android in it: given the rider's choices and what the phone is doing, does the screen
 * light now, and when is the next try. [ScreenKeeper] asks it and does the lighting.
 */
internal object ScreenKeepPolicy {
    /** What made the question come: the screen went off (broadcast), or the timer of a mode with an interval ran out. */
    enum class Trigger(val logName: String) { SCREEN_OFF("screenoff"), TIMER("timer") }

    enum class Decision {
        /** Light the screen. */
        WAKE,

        /** The proximity sensor is covered (a pocket): the screen stays off, and the log says it was skipped. */
        SKIP_PROXIMITY,

        /** Nothing to do now: not on, not projecting, the wrong trigger for the mode, the screen already on, or a light a moment ago. */
        NOTHING,
    }

    /** Nothing lights again within this long of the last light: the screen cannot be turned on and off in a loop that drains the battery. */
    const val MIN_GAP_MS = 3_000L

    /** A distance under this many centimetres is near (a hand, a pocket's cloth), whatever the sensor's own range. */
    private const val NEAR_CM = 5f

    /**
     * The decision. [proximityCovered] is true when the sensor says covered, false when it says free and null when the phone has no such sensor or it
     * did not answer in time (then the screen lights: a rider with no sensor must not lose the function). [sinceLastWakeMs] is null before the first light.
     */
    @Suppress("LongParameterList") // the rider's two choices and the four facts of the moment
    fun decide(
        enabled: Boolean,
        mode: ScreenKeepMode,
        trigger: Trigger,
        screenOn: Boolean,
        proximityCovered: Boolean?,
        sinceLastWakeMs: Long?,
    ): Decision = when {
        !enabled -> Decision.NOTHING
        // At each screen-off only the "always" mode lights at once; the modes with an interval only start counting then.
        trigger == Trigger.SCREEN_OFF && mode != ScreenKeepMode.ON_SCREEN_OFF -> Decision.NOTHING
        trigger == Trigger.TIMER && mode.intervalSec == null -> Decision.NOTHING
        // The timer lights an off screen only; the broadcast of the screen going off is the event itself, so it does not look at the state.
        trigger == Trigger.TIMER && screenOn -> Decision.NOTHING
        sinceLastWakeMs != null && sinceLastWakeMs in 0L until MIN_GAP_MS -> Decision.NOTHING
        proximityCovered == true -> Decision.SKIP_PROXIMITY
        else -> Decision.WAKE
    }

    /**
     * How long until the next light of a mode with an interval: the screen went off at [offSinceMs], and it lights at each multiple of the
     * interval since then (a skip for the proximity does not move the others). Null for the mode with no interval. Always between 1 ms and a whole
     * interval: a clock read exactly on a multiple waits the next one.
     */
    fun nextDelayMs(mode: ScreenKeepMode, offSinceMs: Long, nowMs: Long): Long? {
        val interval = (mode.intervalSec ?: return null) * 1_000L
        val elapsed = (nowMs - offSinceMs).coerceAtLeast(0L)
        return interval - elapsed % interval
    }

    /** Seconds since the screen went off, rounded down, for the log's `after=Ns`. */
    fun secondsOff(offSinceMs: Long, nowMs: Long): Long = ((nowMs - offSinceMs).coerceAtLeast(0L)) / 1_000L

    /**
     * Whether a proximity reading says covered: [distanceCm] is under the sensor's own [maxRangeCm] (many sensors only say 0 or the maximum)
     * and under [NEAR_CM] (a sensor that says centimetres).
     */
    fun covered(distanceCm: Float, maxRangeCm: Float): Boolean = distanceCm < maxRangeCm && distanceCm < NEAR_CM

    /** The ride log's lines, under the tag [TAG] (so the file reads `[screen-keep] on mode=off lockui=off`). */
    const val TAG = "screen-keep"

    /** The line for the function going on. */
    fun onLine(mode: ScreenKeepMode, lockUi: Boolean) = "on mode=${mode.logName} lockui=${if (lockUi) "on" else "off"}"

    /** The line for a light of the screen. */
    fun wakeLine(trigger: Trigger, afterSeconds: Long) = "wake reason=${trigger.logName} after=${afterSeconds}s"

    const val SKIP_LINE = "skip proximity"
    const val OFF_LINE = "off"

    /** The app's activity set showWhenLocked and turnScreenOn (the function and its lock-screen choice are on and the projection runs). */
    const val ARMED_LINE = "lockui armed"

    /** The app's activity was resumed over the lock screen and showed its screen of the function. */
    const val SHOWN_LINE = "lockui shown"

    /** The screen was lit with the phone locked but REau was not the activity in front (the rider was in another app): nothing could show. */
    const val NOT_FRONT_LINE = "lockui not-front"

    /** The screen was lit and REau was in front, but the phone had no lock screen up: nothing to show it over. */
    const val NO_KEYGUARD_LINE = "lockui no-keyguard"

    /**
     * What a moment after a light of the screen says of the lock screen choice: [NOT_FRONT_LINE] when the app's activity is not the resumed one, [NO_KEYGUARD_LINE] when it
     * is and the phone is not locked, and null when it is in front over the lock screen (the activity itself says [SHOWN_LINE] then).
     */
    fun frontLine(activityResumed: Boolean, keyguardLocked: Boolean): String? = when {
        !activityResumed -> NOT_FRONT_LINE
        !keyguardLocked -> NO_KEYGUARD_LINE
        else -> null
    }
}
