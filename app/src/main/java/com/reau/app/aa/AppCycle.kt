package com.reau.app.aa

import com.reau.app.phone.CallState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The joystick action "switch app": walks the first three apps of Android Auto's grid (the order the rider set in Android Auto's own "customise
 * the menu"; REau goes by the positions, not by the names), one at each press: 1, 2, 3, 1, 2, 3...
 *
 * A key of the joystick is one command, and this needs a wait in the middle (the grid takes a moment to open), so it is a sequence that runs in
 * its own coroutine ([press]) and leaves the joystick's thread alone: tap the grid's button of the side bar ([AaRail.GRID_X], [AaRail.GRID_Y]),
 * wait for the screen watch to say the menu is shown (looking every [POLL_MS], up to [OPEN_TIMEOUT_MS]), let the grid settle ([SETTLE_MS]: it
 * animates as it opens) and tap the centre of the next app. With the menu already shown the first tap is skipped. If the grid never opens,
 * nothing else is tapped. The taps go through [send] (AaReceiver.sendInput), which is what tells the screen watch an app was picked
 * ([AaScreens.tapSent]), so the crop follows the app that opens.
 *
 * Pure: the clock, the screen, the pause, the sending and the log are given, so the JVM tests run it with a made-up clock and no waiting.
 */
class AppCycle(
    private val screen: () -> AaScreen,
    private val send: (AaCommand) -> Unit,
    /** Why nothing can be sent now (no Android Auto session, no touch screen announced), or null when it can. */
    private val blocked: () -> String?,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private val busy = AtomicBoolean(false)

    // The position (0-based) the next press taps, and when the last app was tapped (for the screen's word, see [positionFor]).
    @Volatile private var next = 0
    @Volatile private var lastAppMs: Long? = null

    /** A new Android Auto session: the next press taps the first app again. */
    fun restart() {
        next = 0
        lastAppMs = null
    }

    /** Whether a switch is under way. */
    val underWay: Boolean get() = busy.get()

    /**
     * One press of the action, to the end. True when an app was tapped; false when the press was ignored (a switch is already under way) or gave
     * up (nothing can be sent, or the grid did not open), and each of them says so in the log.
     */
    suspend fun press(): Boolean {
        if (!busy.compareAndSet(false, true)) {
            log("app cycle: ignored, the last switch is still under way")
            return false
        }
        try {
            return switch()
        } finally {
            busy.set(false)
        }
    }

    private suspend fun switch(): Boolean {
        val start = now()
        val shown = screen()
        val position = positionFor(shown, start)
        log("app cycle: start, app ${position + 1} of $COUNT next (screen=${shown.name.lowercase()})")
        val why = openGrid(shown, start) ?: settle()
        if (why != null) return gaveUp(why)
        val (x, y) = APPS[position]
        tap(x, y)
        next = (position + 1) % COUNT
        lastAppMs = now()
        log("app cycle: tapped app ${position + 1} of $COUNT at ($x, $y)")
        return true
    }

    /** Opens the grid (or finds it open) and waits for it; null when it is open, otherwise why it is not. */
    @Suppress("ReturnCount") // blocked, the grid that did not open, or open: three answers
    private suspend fun openGrid(shown: AaScreen, start: Long): String? {
        blocked()?.let { return it }
        if (shown == AaScreen.MENU) {
            log("app cycle: the grid is already open, its button skipped")
        } else {
            tap(AaRail.GRID_X, AaRail.GRID_Y)
            if (!gridOpens()) return "the grid did not open within $OPEN_TIMEOUT_MS ms of its button (screen=${screen().name.lowercase()})"
        }
        log("app cycle: grid open after ${now() - start} ms")
        return null
    }

    /** Lets the grid settle, then checks again that something can still be sent; null when it can. */
    private suspend fun settle(): String? {
        pause(SETTLE_MS)
        return blocked()
    }

    /**
     * The app to tap now. Normally the one after the last tapped. An app screen (Spotify, or anything that is not the menu and not a map) takes it back to
     * the first, since after the third the walk starts again and an app of another way in has no place in it; but the screen right after one of this
     * walk's own taps is the watch's guess that an app is opening (it says "apps" until the picture says the map), so for [OWN_TAP_QUIET_MS] after
     * a tap it is no news.
     */
    private fun positionFor(shown: AaScreen, nowMs: Long): Int {
        val last = lastAppMs
        val ownTap = last != null && nowMs - last < OWN_TAP_QUIET_MS
        return if (shown == AaScreen.APPS && !ownTap) 0 else next
    }

    /** Waits for the screen to be the menu, [POLL_MS] apart, at most [OPEN_TIMEOUT_MS] from now; false when it never was. */
    private suspend fun gridOpens(): Boolean {
        val from = now()
        while (screen() != AaScreen.MENU) {
            if (now() - from >= OPEN_TIMEOUT_MS) return false
            pause(POLL_MS)
        }
        return true
    }

    /** A finger down and up at ([x], [y]), [TAP_MS] apart; the lift goes even when the coroutine is cancelled in between. */
    private suspend fun tap(x: Int, y: Int) {
        send(AaCommand.Touch(PhoneTouch.Action.DOWN, x, y))
        try {
            pause(TAP_MS)
        } finally {
            send(AaCommand.Touch(PhoneTouch.Action.UP, x, y))
        }
    }

    private fun gaveUp(why: String): Boolean {
        log("app cycle: gave up, $why; no app tapped")
        return false
    }

    companion object {
        /**
         * Why the switch cannot act now, or null when it can: no Android Auto session, a touch screen not announced to it, or a call ringing or under
         * way (the apps menu over the call's card would be in the way; the dash's sides belong to the call as well). The call state is
         * [CallState.IDLE] unless the rider switched answering calls on, so without that switch a call does not block.
         */
        fun blockedReason(androidAutoActive: Boolean, touchAnnounced: Boolean, call: CallState): String? = when {
            !androidAutoActive -> "Android Auto is not connected"
            !touchAnnounced -> "Android Auto was not told of a touch screen"
            call != CallState.IDLE -> "a call is ringing or under way"
            else -> null
        }

        /** The apps of the walk. */
        const val COUNT = 3

        /** The centres of the first three icons of the grid at 800×480, 180 dpi: two columns, the third below the first. */
        val APPS: List<Pair<Int, Int>> = listOf(271 to 50, 618 to 50, 271 to 195)

        /** From the finger down to its lift. */
        const val TAP_MS = 80L

        /** How often the screen is looked at while the grid opens, and how long it is waited for. */
        const val POLL_MS = 100L
        const val OPEN_TIMEOUT_MS = 1_500L

        /** After the menu is recognised: the grid animates as it opens, and a tap while it moves misses. */
        const val SETTLE_MS = 250L

        /** After a tap of the walk, the screen "apps" is its own doing, not a sign that the rider went to another app. */
        const val OWN_TAP_QUIET_MS = 4_000L
    }
}
