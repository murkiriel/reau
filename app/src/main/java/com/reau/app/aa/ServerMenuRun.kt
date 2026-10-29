package com.reau.app.aa

/**
 * One walk through Android Auto's settings to switch its head unit server on ([Mode.START]) or off ([Mode.STOP]):
 * open the settings, wait for the ⋮ button, open the menu, read it, touch the one item that is wanted, go back.
 * Pure: it is shown what the screen holds ([Seen]) and answers with the next thing to do ([Step]); the accessibility
 * service carries it out.
 *
 * The rules that keep the rider's Android Auto safe:
 * - an item is touched only when its text equals the wanted one ([ServerTexts]), never by place, and never one of the
 *   developer items ("Sair do modo de desenvolvedor" is right under the server's);
 * - one touch of the wanted item per walk; "Versão" (the 10 taps of developer mode) is never touched;
 * - the phone locked, another app in front or "Cancelar" on the cover: it stops at once, with no other touch.
 *
 * In [Mode.START], a menu that shows "stop" (Android Auto holds the server as running, yet nothing answered) restarts
 * it once when [restartMarkedRunning]: "stop", [RESTART_PAUSE_MS], the menu again, "start".
 */
// One walk's life: a function per phase, and the few calls the service makes into it; split, they would only pass the
// same state around.
@Suppress("TooManyFunctions")
class ServerMenuRun(
    val mode: Mode,
    private val texts: ServerTexts,
    startMs: Long,
    private val restartMarkedRunning: Boolean = mode == Mode.START,
    private val timing: Timing = Timing(),
    /** The app whose menu is walked: Android Auto; a test's own screen on the emulator. */
    val target: String = AndroidAuto.PACKAGE,
) {
    /**
     * [START] switches the server on, [STOP] off. [VISIT] only opens Android Auto's settings and keeps them in front until the port
     * answers or [Timing.visitMs] runs out, touching nothing: in the logs of 19 and 20/10/2026 the server answered 0.2 to 1.2 s after
     * the rider opened those settings in 5 of 7 visits, against 2 of 6 for a second stop-and-start with "back" 110 ms later.
     */
    enum class Mode { START, STOP, VISIT }

    /** The longest each part may take; the walk moves on as soon as the screen allows. */
    data class Timing(
        val totalMs: Long = 10_000,
        val openMs: Long = 4_000,
        val overflowMs: Long = 3_000,
        val quietMs: Long = 400,
        val listMs: Long = 1_500,
        val listSteadyMs: Long = 300,
        val reopenPauseMs: Long = 500,
        val listCloseMs: Long = 600,
        val backEveryMs: Long = 500,
        val leaveMs: Long = 2_500,
        /**
         * After "start" was touched, Android Auto stays in front this long at most while the server comes up: with "back" 110 ms
         * after the touch, an Android Auto that was cold (or held back by the power saver) only started the server once its settings
         * came to the front again (the 19/10/2026 logs: the port refused for 8.6 to 22.5 s, and answered 0.2 to 1 s after the
         * settings reopened). It goes back sooner, [holdAfterUpMs] after the port answers.
         */
        val holdMs: Long = 8_000,
        val holdAfterUpMs: Long = 600,
        /** A [Mode.VISIT] keeps Android Auto's settings in front this long at most, waiting for the port. */
        val visitMs: Long = 4_000,
        /**
         * A menu with only the help items ("Ajuda e feedback | Enviar feedback") is read again, with a "back" and the ⋮ touched
         * again, for this long counted from the first time it was read, before it is taken as developer mode being off. Read twice
         * in 4.3 s and 4.9 s, a cold Android Auto that was still building its menu gave a false "developer mode off" (12:54 and
         * 13:02 of 19/10/2026); the walk that followed, with Android Auto already open, read the whole menu. The walk's own time is
         * longer by this much when the wait begins.
         */
        val halfMenuMs: Long = 5_000,
    )

    /** What the screen holds, as the accessibility service sees it. */
    data class Seen(
        val nowMs: Long,
        /** The package of the window in front; null when none is known. */
        val front: String?,
        /** Locked, or the screen off. */
        val locked: Boolean,
        /** Every node with a text or a description in Android Auto's windows. */
        val nodes: List<Node>,
        /** When Android Auto last said its content changed (0: never). */
        val lastChangeMs: Long = 0,
        /** Whether the server's port answered since this walk began (read by the service from [AaServerStarter]). */
        val portUp: Boolean = false,
    )

    /** One node: its [window], what it says, whether it or an ancestor can be clicked, and [ref] for the service. */
    data class Node(val window: Int, val text: String?, val description: String?, val clickable: Boolean, val ref: Int)

    sealed interface Step {
        /** Open Android Auto's settings ([AndroidAuto]'s DefaultSettingsActivity). */
        data object OpenSettings : Step

        /** Click [node] (its first clickable ancestor); [what] for the log. Say [clickRefused] if Android said no. */
        data class Click(val node: Node, val what: String) : Step

        data object Back : Step

        /** Bring REau to the front: Android Auto did not go with "back". */
        data object OpenREau : Step

        data object Wait : Step

        data class Done(val outcome: Outcome) : Step
    }

    enum class Outcome {
        /** "Start" was touched; whether the port answers is checked by the caller. */
        TAPPED_START,

        /** "Stop" was touched. */
        TAPPED_STOP,

        /** [Mode.VISIT]: the settings were in front for the wait and nothing was touched; whether the port answered is [ServerMenuRun.succeeded]. */
        VISITED,

        /** [Mode.START]: the menu shows "stop" and no restart was allowed (or it was already done once). */
        ALREADY_RUNNING,

        /** [Mode.START]: the menu shows "stop" and the port already answers: the server is up, so nothing is stopped or started. */
        ALREADY_UP,

        /** [Mode.STOP]: the menu shows "start": nothing to stop. */
        ALREADY_STOPPED,
        DEVELOPER_MODE_OFF,
        UNKNOWN_MENU,
        MENU_DID_NOT_OPEN,
        UNEXPECTED_SCREEN,
        SETTINGS_DID_NOT_OPEN,
        CLICK_REFUSED,
        TIMED_OUT,
        LOCKED,
        OTHER_APP,
        CANCELLED,

        /** Android Auto did not answer a read of its screen in time: its process is gone (it was seen to crash as the stop walk began). */
        AA_GONE,
    }

    private enum class Phase { OPENING, SETTLING, MENU, LIST, HOLDING, LEAVING, FINISHED }

    private var phase = Phase.OPENING
    private var phaseSince = startMs
    private var deadline = startMs + timing.totalMs
    private var outcome: Outcome? = null
    private var opened = false
    private var cancelled = false

    // The window of the ⋮ button: the menu's list is any other window of Android Auto.
    private var overflowWindow: Int? = null
    private var overflowClicks = 0
    private var halfSince = 0L
    private var pauseUntil = 0L
    private var listTexts: List<String>? = null
    private var listSteadySince = 0L
    private var backs = 0
    private var lastBackMs = 0L
    private var upSince = 0L
    private var touchedAt = 0L

    // The furthest of the cover's three steps the walk got to (0 opening Android Auto, 1 its menu, 2 the list / the wait).
    private var reached = 0

    /** Whether a restart ("stop", then "start") happened in this walk. */
    var restarted = false
        private set

    /**
     * Where the walk is, for the cover's three steps: 0 opening Android Auto, 1 its developer
     * menu, 2 starting (or stopping) the server in the menu's list, 3 every step done.
     */
    val coverStep: Int get() = when (phase) {
        Phase.OPENING -> 0
        Phase.SETTLING, Phase.MENU -> 1
        Phase.LIST, Phase.HOLDING -> 2
        // Every step done only when the walk did what it came for: a walk that left because the menu was not the one, or the
        // server never answered, stays where it failed, and the cover does not say "done" over it.
        Phase.LEAVING, Phase.FINISHED -> if (succeeded) COVER_STEPS else reached.coerceAtMost(COVER_STEPS - 1)
    }

    /**
     * The walk got what it came for: "stop" touched, the server already stopped, or "start" touched and the port answered.
     * Meaningful once the walk is leaving; before that, false.
     */
    val succeeded: Boolean get() = when (outcome) {
        Outcome.TAPPED_STOP, Outcome.ALREADY_STOPPED, Outcome.ALREADY_UP -> true
        Outcome.TAPPED_START, Outcome.VISITED -> upSince != 0L
        else -> false
    }

    /**
     * "Start" was touched and Android Auto is being held in front until the server answers: the service asks the receiver to
     * dial at each of its ticks meanwhile (a dial that is refused costs nothing).
     */
    val holding: Boolean get() = phase == Phase.HOLDING

    /** The texts of the last menu read, for the log ("texts: …"). */
    var lastMenu: List<String> = emptyList()
        private set

    /** The step after [seen]. */
    @Suppress("ReturnCount") // what ends a walk in any phase comes first, each its own early answer
    fun next(seen: Seen): Step {
        if (phase == Phase.FINISHED) return Step.Done(checkNotNull(outcome))
        // Cancelled on the way back, the result stays (a touch already made counts); either way, no other touch.
        // A touch already made counts: "start" stays the result when the walk is cut short while it holds.
        if (cancelled) return finish(if (phase == Phase.LEAVING || phase == Phase.HOLDING) checkNotNull(outcome) else Outcome.CANCELLED)
        if (phase == Phase.LEAVING) return leaving(seen)
        if (phase == Phase.HOLDING) return holding(seen)
        interruption(seen)?.let { return finish(it) }
        if (seen.nowMs >= deadline) return leave(Outcome.TIMED_OUT, seen)
        return when (phase) {
            Phase.OPENING -> opening(seen)
            Phase.SETTLING -> settling(seen)
            Phase.MENU -> menu(seen)
            Phase.LIST -> list(seen)
            Phase.HOLDING, Phase.LEAVING, Phase.FINISHED -> Step.Wait
        }
    }

    /**
     * After the touch on "start": no touch, Android Auto kept in front until the port answers (and [Timing.holdAfterUpMs] more,
     * for it to finish drawing) or [Timing.holdMs] runs out. The phone locking or another app coming to the front ends the walk
     * with no touch and the result the touch already made; the walk's own deadline does not apply here.
     */
    private fun holding(seen: Seen): Step {
        val elsewhere = seen.front != null && seen.front != target && seen.front != REAU
        if (seen.locked || elsewhere) return finish(checkNotNull(outcome))
        if (seen.portUp && upSince == 0L) upSince = seen.nowMs
        val upLongEnough = upSince != 0L && seen.nowMs - upSince >= timing.holdAfterUpMs
        val limit = if (mode == Mode.VISIT) timing.visitMs else timing.holdMs
        return if (upLongEnough || seen.nowMs - phaseSince >= limit) leave(checkNotNull(outcome), seen) else Step.Wait
    }

    /** The phone locked, or (once Android Auto has opened) another app in front: the walk stops with no touch. */
    private fun interruption(seen: Seen): Outcome? {
        val elsewhere = seen.front != null && seen.front != target && seen.front != REAU
        return when {
            seen.locked -> Outcome.LOCKED
            phase != Phase.OPENING && elsewhere -> Outcome.OTHER_APP
            else -> null
        }
    }

    /** "Cancelar" on the cover: the walk ends with no other touch. */
    fun cancel() {
        cancelled = true
    }

    /**
     * Android Auto did not answer a read in time ([ReadWatch]): the walk ends, with no other touch. A touch already made counts, as
     * when the rider cancels (the start held, the stop on its way back); the result is what the walk ends with.
     */
    fun gone(): Outcome {
        if (phase != Phase.FINISHED) {
            if (phase != Phase.HOLDING && phase != Phase.LEAVING) outcome = Outcome.AA_GONE
            phase = Phase.FINISHED
        }
        return checkNotNull(outcome)
    }

    /** Android could not open the settings. */
    fun openFailed() {
        if (phase != Phase.FINISHED) {
            outcome = Outcome.SETTINGS_DID_NOT_OPEN
            phase = Phase.FINISHED
        }
    }

    /** The last [Step.Click] was refused: no other touch, the walk leaves. */
    fun clickRefused(nowMs: Long) {
        if (phase == Phase.FINISHED) return
        outcome = Outcome.CLICK_REFUSED
        enter(Phase.LEAVING, nowMs)
    }

    @Suppress("ReturnCount") // the touch to open first, then a visit or the settling, then the wait for Android Auto to come
    private fun opening(seen: Seen): Step {
        if (!opened) {
            opened = true
            return Step.OpenSettings
        }
        if (seen.front == target) {
            if (mode == Mode.VISIT) return visit(seen)
            enter(Phase.SETTLING, seen.nowMs)
            return settling(seen)
        }
        return if (seen.nowMs - phaseSince >= timing.openMs) leave(Outcome.SETTINGS_DID_NOT_OPEN, seen) else Step.Wait
    }

    /** A visit: Android Auto's settings are in front; no touch, only the wait for the port ([holding] does it). */
    private fun visit(seen: Seen): Step {
        outcome = Outcome.VISITED
        enter(Phase.HOLDING, seen.nowMs)
        return holding(seen)
    }

    private fun settling(seen: Seen): Step {
        val overflow = overflowOf(seen)
        if (overflow != null && seen.nowMs - seen.lastChangeMs >= timing.quietMs) {
            overflowWindow = overflow.window
            enter(Phase.MENU, seen.nowMs)
            return menu(seen)
        }
        return if (seen.nowMs - phaseSince >= timing.overflowMs) leave(Outcome.UNEXPECTED_SCREEN, seen) else Step.Wait
    }

    private fun menu(seen: Seen): Step {
        if (seen.nowMs < pauseUntil || listOpen(seen)) return Step.Wait
        val overflow = overflowOf(seen)
            ?: return if (seen.nowMs - phaseSince >= timing.overflowMs) leave(Outcome.UNEXPECTED_SCREEN, seen) else Step.Wait
        overflowWindow = overflow.window
        overflowClicks++
        listTexts = null
        enter(Phase.LIST, seen.nowMs)
        return Step.Click(overflow, "more options")
    }

    @Suppress("ReturnCount") // the waits first, each its own early answer; then the reading
    private fun list(seen: Seen): Step {
        val items = listNodes(seen)
        val shown = items.mapNotNull { it.text?.takeIf(String::isNotBlank) }
        if (shown.isEmpty()) {
            if (seen.nowMs - phaseSince < timing.listMs) return Step.Wait
            return if (overflowClicks < MAX_OVERFLOW_CLICKS) {
                enter(Phase.MENU, seen.nowMs)
                menu(seen)
            } else {
                leave(Outcome.MENU_DID_NOT_OPEN, seen)
            }
        }
        if (shown != listTexts) {
            listTexts = shown
            listSteadySince = seen.nowMs
        }
        if (seen.nowMs - listSteadySince < timing.listSteadyMs) return Step.Wait
        lastMenu = shown
        return when (texts.read(shown)) {
            ServerTexts.Reading.START_SHOWN ->
                if (mode == Mode.START) tap(items.first { texts.isStart(it.text) }, Outcome.TAPPED_START, seen) else leave(Outcome.ALREADY_STOPPED, seen)
            ServerTexts.Reading.STOP_SHOWN -> stopShown(items.first { texts.isStop(it.text) }, seen)
            ServerTexts.Reading.UNKNOWN_DEVELOPER_MENU -> leave(Outcome.UNKNOWN_MENU, seen)
            // The help items only may be the menu drawn before Android Auto's settings loaded (a cold Android Auto takes a while):
            // read again for [Timing.halfMenuMs] from the first time; only then the developer mode is taken as off.
            ServerTexts.Reading.DEVELOPER_MODE_OFF -> halfMenu(seen)
            ServerTexts.Reading.UNREADABLE ->
                if (overflowClicks < MAX_OVERFLOW_CLICKS) reopen(seen) else leave(Outcome.MENU_DID_NOT_OPEN, seen)
        }
    }

    private fun halfMenu(seen: Seen): Step {
        if (halfSince == 0L) {
            halfSince = seen.nowMs
            deadline += timing.halfMenuMs
        }
        if (seen.nowMs - halfSince >= timing.halfMenuMs) return leave(Outcome.DEVELOPER_MODE_OFF, seen)
        overflowClicks = 0  // each read has its own two touches of the ⋮: a long wait is not "the menu did not open"
        return reopen(seen)
    }

    private fun stopShown(stop: Node, seen: Seen): Step = when {
        mode == Mode.STOP -> tap(stop, Outcome.TAPPED_STOP, seen)
        // The port answers: the server is up, whatever the menu says; stopping it would only take a good server down (20:01 on 20/10/2026).
        restartMarkedRunning && !restarted && seen.portUp -> leave(Outcome.ALREADY_UP, seen)
        restartMarkedRunning && !restarted -> {
            // Marked as running and not answering: stopped, and started again after a pause, once.
            restarted = true
            deadline += RESTART_EXTRA_MS
            overflowClicks = 0
            halfSince = 0
            pauseUntil = seen.nowMs + RESTART_PAUSE_MS
            enter(Phase.MENU, seen.nowMs)
            Step.Click(stop, "stop (restart)")
        }
        else -> leave(Outcome.ALREADY_RUNNING, seen)
    }

    private fun tap(node: Node, result: Outcome, seen: Seen): Step {
        outcome = result
        touchedAt = seen.nowMs
        // "Start" is followed by the hold; "stop" has nothing to wait for.
        enter(if (result == Outcome.TAPPED_START) Phase.HOLDING else Phase.LEAVING, seen.nowMs)
        return Step.Click(node, if (result == Outcome.TAPPED_START) "start" else "stop")
    }

    /** The list closed with "back", and the ⋮ touched again after a pause. */
    private fun reopen(seen: Seen): Step {
        pauseUntil = seen.nowMs + timing.reopenPauseMs
        enter(Phase.MENU, seen.nowMs)
        return Step.Back
    }

    private fun leave(result: Outcome, seen: Seen): Step {
        outcome = result
        enter(Phase.LEAVING, seen.nowMs)
        return leaving(seen)
    }

    /**
     * Back to where the rider was: the list closes, then "back" while Android Auto is in front, up to three, then
     * REau itself. Locked or another app in front: it ends with no touch.
     */
    @Suppress("ReturnCount") // one early answer per rule of the way back, in the order they apply
    private fun leaving(seen: Seen): Step {
        val result = checkNotNull(outcome)
        // A window in front that is not Android Auto's (REau's, once the way back is done, or another app) ends the walk; none known does not:
        // right after the touch the menu is closing and no window of the phone's screen is active for a moment (23/10/2026: the walk of stop
        // ended with "no window in front" and no back, and Android Auto's settings stayed in front of REau).
        if (seen.locked || (seen.front != null && seen.front != target)) return finish(result)
        val waited = seen.nowMs - phaseSince
        if (waited >= timing.leaveMs) return finishWith(Step.OpenREau, result)
        // The list closes some time after the touch, whether the walk held in front meanwhile or not.
        val sinceTouch = if (touchedAt != 0L) seen.nowMs - touchedAt else waited
        if (listOpen(seen) && sinceTouch < timing.listCloseMs) return Step.Wait
        if (backs > 0 && seen.nowMs - lastBackMs < timing.backEveryMs) return Step.Wait
        if (backs >= MAX_BACKS) return finishWith(Step.OpenREau, result)
        backs++
        lastBackMs = seen.nowMs
        return Step.Back
    }

    private fun finish(result: Outcome): Step {
        outcome = result
        phase = Phase.FINISHED
        return Step.Done(result)
    }

    /** [step] now; the walk is over and the next call says [result]. */
    private fun finishWith(step: Step, result: Outcome): Step {
        outcome = result
        phase = Phase.FINISHED
        return step
    }

    private fun enter(next: Phase, nowMs: Long) {
        phase = next
        phaseSince = nowMs
        reached = maxOf(reached, when (next) {
            Phase.OPENING -> 0
            Phase.SETTLING, Phase.MENU -> 1
            Phase.LIST, Phase.HOLDING -> COVER_STEPS - 1
            Phase.LEAVING, Phase.FINISHED -> 0
        })
    }

    private fun overflowOf(seen: Seen): Node? = seen.nodes.firstOrNull { it.clickable && texts.isMoreOptions(it.description) }

    /** The menu's list: Android Auto's nodes in a window other than the ⋮ button's. */
    private fun listNodes(seen: Seen): List<Node> {
        val home = overflowWindow ?: return emptyList()
        return seen.nodes.filter { it.window != home }
    }

    private fun listOpen(seen: Seen) = listNodes(seen).isNotEmpty()

    companion object {
        const val REAU = "com.reau.app"

        /**
         * Between "stop" and "start" in a restart. The 1.5 s and the order (stop, pause, start) are the ones HeadQLink, a fork of
         * open-headunit for a car (AGPL-3.0, NOTICE), uses in its own restart of Android Auto's server (AaServerStarter.restartAndWait);
         * only the value and the order are taken, no code.
         */
        const val RESTART_PAUSE_MS = 1_500L

        /** A restart reads the menu twice: the walk gets this much longer. */
        const val RESTART_EXTRA_MS = 4_000L

        /** The longest a walk can go on: its total time, a restart's extra time, the pause between its halves, the hold after "start", the wait over a half-built menu and the way back. */
        fun longestWalkMs(timing: Timing = Timing()): Long =
            timing.totalMs + RESTART_EXTRA_MS + RESTART_PAUSE_MS + timing.holdMs + timing.halfMenuMs + timing.leaveMs

        /** Room beyond the walk and the cover's tail before the cover's watchdog may take it down. */
        private const val WATCHDOG_SLACK_MS = 1_000L

        /**
         * When the cover's watchdog goes off: after the longest walk and the cover's own [coverAfterMs] tail, with some room.
         * A fixed 16 s was shorter than the 16.5 s a restart's walk and tail can take, so the cover could come down in the
         * middle of one (audit of 20/10/2026).
         */
        fun coverWatchdogMs(coverAfterMs: Long, timing: Timing = Timing()): Long = longestWalkMs(timing) + coverAfterMs + WATCHDOG_SLACK_MS
        /** The cover shows three steps; all done is this many. */
        private const val COVER_STEPS = 3
        private const val MAX_OVERFLOW_CLICKS = 2
        private const val MAX_BACKS = 3
    }
}
