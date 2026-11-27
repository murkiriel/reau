package com.reau.app.service

/**
 * The decision of "Tela acesa com o celular bloqueado", with no Android in it: given the rider's choice and what the function has just done, does the screen
 * light now (the moment it goes off). [ScreenKeeper] asks it and does the lighting. The modes by interval (every 30 s to 4 min) were tried in the 0.3.9x series and
 * could not light a screen that was already locked: only "sempre que a tela apagar" is left.
 */
internal object ScreenKeepPolicy {
    /** Nothing lights again within this long of the last light: the screen cannot be turned on and off in a loop that drains the battery. */
    const val MIN_GAP_MS = 3_000L

    /**
     * Why nothing is lit, in one word for the log, or null when the screen is to be lit. [sinceLastWakeMs] is null before the first light. [pausedByRider]: the rider
     * paused the function for this projection. It lights wherever the phone is, a pocket too: the rider wanted the function to hold the phone awake, so no sensor is asked.
     */
    fun why(enabled: Boolean, sinceLastWakeMs: Long?, pausedByRider: Boolean = false): String? = when {
        !enabled -> WHY_OFF
        // "Apagar a tela nesta viagem": the rider asked for the screen to go off, for this projection only.
        pausedByRider -> WHY_PAUSED
        sinceLastWakeMs != null && sinceLastWakeMs in 0L until MIN_GAP_MS -> WHY_GAP
        else -> null
    }

    const val WHY_OFF = "off"
    const val WHY_PAUSED = "paused"
    const val WHY_GAP = "gap"

    /** The line of a decision not to light the screen: the reason. */
    fun nothingLine(why: String) = "nothing trigger=screenoff reason=$why"

    /** Where a change of the screen's state was seen: the display's listener, the SCREEN_OFF/SCREEN_ON broadcast, or the safety tick. */
    enum class Via(val logName: String) { LISTENER("listener"), BROADCAST("broadcast"), TICK("tick") }

    /** How often the safety tick looks at the display while the function is on and the projection runs: it serves when the display's listener fails. */
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

    /** Seconds since the screen went off, rounded down, for the log's `after=Ns`. */
    fun secondsOff(offSinceMs: Long, nowMs: Long): Long = ((nowMs - offSinceMs).coerceAtLeast(0L)) / 1_000L

    /** The ride log's lines, under the tag [TAG] (so the file reads `[screen-keep] on mode=off lockui=off`). */
    const val TAG = "screen-keep"

    /** The line for the function going on (`mode=off` is the one mode of the light, "sempre que a tela apagar", and `lockui=on` the screen over the lock: kept as the readers of the log know them). */
    const val ON_LINE = "on mode=off lockui=on"

    /** The line for a light of the screen, with what asked for it: the screen going off ([REASON_SCREENOFF]), the function beginning with the screen already off ([REASON_START]) or the safety tick finding it still dark ([REASON_DARK]). */
    fun wakeLine(afterSeconds: Long, reason: String = REASON_SCREENOFF) = "wake reason=$reason after=${afterSeconds}s"

    const val REASON_SCREENOFF = "screenoff"
    const val REASON_START = "start"
    const val REASON_DARK = "dark"

    /** How long after a light the screen must still be off for the safety tick to light it again (the gap [MIN_GAP_MS] is respected by the decision as always). */
    const val DARK_AFTER_MS = MIN_GAP_MS

    /** How many times in a row the tick lights a screen that stays dark: a phone that ignores the light is not asked for ever (the count starts again when the screen is seen on). */
    const val MAX_DARK_TRIES = 3

    /**
     * Whether the safety tick lights the screen again: the display is still off ([displayOff]) after a light was asked ([lightAsked], and the screen not seen on since),
     * [sinceLastWakeMs] ago, which is at least [DARK_AFTER_MS], and fewer than [MAX_DARK_TRIES] tries were made. A light that did not light the screen is not repeated by any event,
     * for the state of the display did not change.
     */
    fun darkRetry(displayOff: Boolean, lightAsked: Boolean, sinceLastWakeMs: Long?, tries: Int): Boolean =
        displayOff && lightAsked && sinceLastWakeMs != null && sinceLastWakeMs >= DARK_AFTER_MS && tries < MAX_DARK_TRIES

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
