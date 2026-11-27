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

        /** Nothing to do now: not on, paused by the rider, the wrong trigger for the mode, the screen already on, or a light a moment ago. */
        NOTHING,
    }

    /** Nothing lights again within this long of the last light: the screen cannot be turned on and off in a loop that drains the battery. */
    const val MIN_GAP_MS = 3_000L

    /**
     * The decision. [sinceLastWakeMs] is null before the first light. [pausedByRider]: the rider paused the function for this projection.
     * It lights wherever the phone is, a pocket too: the rider wanted the function to hold the phone awake, so no sensor is asked.
     */
    @Suppress("LongParameterList") // the rider's two choices and the facts of the moment
    fun decide(
        enabled: Boolean,
        mode: ScreenKeepMode,
        trigger: Trigger,
        screenOn: Boolean,
        sinceLastWakeMs: Long?,
        pausedByRider: Boolean = false,
    ): Decision = if (why(enabled, mode, trigger, screenOn, sinceLastWakeMs, pausedByRider) == null) Decision.WAKE else Decision.NOTHING

    /** Why nothing is lit, in one word for the log, or null when the screen is to be lit ([decide] is this, as a decision). */
    @Suppress("LongParameterList") // the same facts as [decide]
    fun why(
        enabled: Boolean,
        mode: ScreenKeepMode,
        trigger: Trigger,
        screenOn: Boolean,
        sinceLastWakeMs: Long?,
        pausedByRider: Boolean = false,
    ): String? = when {
        !enabled -> WHY_OFF
        // "Apagar a tela nesta viagem": the rider asked for the screen to go off, for this projection only.
        pausedByRider -> WHY_PAUSED
        // At each screen-off only the "always" mode lights at once; the modes with an interval only start counting then.
        trigger == Trigger.SCREEN_OFF && mode != ScreenKeepMode.ON_SCREEN_OFF -> WHY_TRIGGER
        trigger == Trigger.TIMER && mode.intervalSec == null -> WHY_TRIGGER
        // The timer lights an off screen only; the broadcast of the screen going off is the event itself, so it does not look at the state.
        trigger == Trigger.TIMER && screenOn -> WHY_SCREEN_ON
        sinceLastWakeMs != null && sinceLastWakeMs in 0L until MIN_GAP_MS -> WHY_GAP
        else -> null
    }

    const val WHY_OFF = "off"
    const val WHY_PAUSED = "paused"
    const val WHY_TRIGGER = "wrong-trigger"
    const val WHY_SCREEN_ON = "screen-on"
    const val WHY_GAP = "gap"

    /** The line of a decision not to light the screen: the trigger and the reason. */
    fun nothingLine(trigger: Trigger, why: String) = "nothing trigger=${trigger.logName} reason=$why"

    /** Where a change of the screen's state was seen: the display's listener, the SCREEN_OFF/SCREEN_ON broadcast, or the safety tick. */
    enum class Via(val logName: String) { LISTENER("listener"), BROADCAST("broadcast"), TICK("tick") }

    /** How often the safety tick looks at the display while the function is on and the projection runs. */
    const val TICK_MS = 5_000L

    /**
     * What the function knows of the screen: off or on, from the default display's state (during a projection of Android Auto the phone stays "interactive" and no
     * SCREEN_OFF broadcast comes, found in the 0.3.92 test). The three sources say the same thing, so a change is taken once, from whoever sees it first.
     */
    class DisplayEdge(off: Boolean = false) {
        var off = off
            private set

        /** [nowOff] was seen by [via]: the log line of the change, or null when the screen was already known to be so. */
        fun heard(nowOff: Boolean, via: Via): String? {
            if (nowOff == off) return null
            off = nowOff
            return "display ${if (nowOff) "off" else "on"} via=${via.logName}"
        }

        /** The screen is known to be [nowOff] with no change to say (the function begins). */
        fun reset(nowOff: Boolean) {
            off = nowOff
        }
    }

    /** Whether a `Display.STATE_*` of the default display is the screen gone off: anything but on (`Display.STATE_ON`, 2). */
    fun displayOff(state: Int): Boolean = state != DISPLAY_ON

    private const val DISPLAY_ON = 2

    /**
     * How long until the next light of a mode with an interval: the screen went off at [offSinceMs], and it lights at each multiple of the
     * interval since then (a light the folga mínima holds back does not move the others). Null for the mode with no interval. Always between 1 ms and a whole
     * interval: a clock read exactly on a multiple waits the next one.
     */
    fun nextDelayMs(mode: ScreenKeepMode, offSinceMs: Long, nowMs: Long): Long? {
        val interval = (mode.intervalSec ?: return null) * 1_000L
        val elapsed = (nowMs - offSinceMs).coerceAtLeast(0L)
        return interval - elapsed % interval
    }

    /** Seconds since the screen went off, rounded down, for the log's `after=Ns`. */
    fun secondsOff(offSinceMs: Long, nowMs: Long): Long = ((nowMs - offSinceMs).coerceAtLeast(0L)) / 1_000L

    /** The ride log's lines, under the tag [TAG] (so the file reads `[screen-keep] on mode=off lockui=off`). */
    const val TAG = "screen-keep"

    /** The line for the function going on. */
    fun onLine(mode: ScreenKeepMode, lockUi: Boolean) = "on mode=${mode.logName} lockui=${if (lockUi) "on" else "off"}"

    /** The line for a light of the screen. */
    fun wakeLine(trigger: Trigger, afterSeconds: Long) = "wake reason=${trigger.logName} after=${afterSeconds}s"

    const val OFF_LINE = "off"

    /** The rider pressed "Apagar a tela nesta viagem" on the screen over the lock screen. */
    const val PAUSED_LINE = "paused by rider"

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
