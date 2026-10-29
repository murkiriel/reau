package com.reau.app.aa

/**
 * What the automatic switch of Android Auto's head unit server says on the screen and in the log. [NONE]: nothing
 * tried yet.
 */
enum class ServerResult {
    NONE,

    /** A walk through Android Auto's menu is going on: switching the server on. */
    WORKING,

    /** A walk through Android Auto's menu is going on: switching the server off. */
    STOPPING,

    /** The port answered after "start" was touched. */
    STARTED,
    SERVICE_OFF,
    LOCKED,
    NOT_INSTALLED,
    SETTINGS_DID_NOT_OPEN,
    UNEXPECTED_SCREEN,
    MENU_DID_NOT_OPEN,
    DEVELOPER_MODE_OFF,
    UNKNOWN_MENU,

    /** The menu shows "stop" (the server marked running) and the port does not answer. */
    ALREADY_RUNNING,

    /** "Start" touched, and the port still closed after the wait. */
    NOT_UP,
    INTERRUPTED,

    /** Android Auto stopped answering while the walk read its screen (its process was gone). */
    AA_GONE,

    /** Switched off after the projection: the notification's "Stop", or the menu's. */
    STOPPED,

    /** The projection ended and the server could not be switched off (no "Stop" button kept, phone locked). */
    LEFT_ON,
    ;

    companion object {
        /** What a walk's [outcome] means for the rider; a touched "start" waits for the port ([WORKING]). */
        /** What a walk in [mode] says while it goes on. */
        fun working(mode: ServerMenuRun.Mode): ServerResult = if (mode == ServerMenuRun.Mode.STOP) STOPPING else WORKING

        /** What the screen says once the port answered late: started, unless a later thing already said something else. */
        internal fun afterLateAnswer(current: ServerResult): ServerResult =
            if (current == NOT_UP || current == WORKING) STARTED else current

        internal fun of(outcome: ServerMenuRun.Outcome): ServerResult = when (outcome) {
            ServerMenuRun.Outcome.TAPPED_START, ServerMenuRun.Outcome.VISITED -> WORKING
            ServerMenuRun.Outcome.ALREADY_UP -> STARTED
            ServerMenuRun.Outcome.TAPPED_STOP, ServerMenuRun.Outcome.ALREADY_STOPPED -> STOPPED
            ServerMenuRun.Outcome.ALREADY_RUNNING -> ALREADY_RUNNING
            ServerMenuRun.Outcome.DEVELOPER_MODE_OFF -> DEVELOPER_MODE_OFF
            ServerMenuRun.Outcome.UNKNOWN_MENU -> UNKNOWN_MENU
            ServerMenuRun.Outcome.MENU_DID_NOT_OPEN, ServerMenuRun.Outcome.CLICK_REFUSED -> MENU_DID_NOT_OPEN
            ServerMenuRun.Outcome.UNEXPECTED_SCREEN -> UNEXPECTED_SCREEN
            ServerMenuRun.Outcome.SETTINGS_DID_NOT_OPEN -> SETTINGS_DID_NOT_OPEN
            ServerMenuRun.Outcome.LOCKED, ServerMenuRun.Outcome.OTHER_APP, ServerMenuRun.Outcome.CANCELLED,
            ServerMenuRun.Outcome.TIMED_OUT -> INTERRUPTED
            ServerMenuRun.Outcome.AA_GONE -> AA_GONE
        }
    }
}

/**
 * Whether a walk may begin, in the order the rider would fix them: the switch, the
 * accessibility service, the phone unlocked with the screen on, Android Auto there. Null: go.
 */
internal object ServerGate {
    data class Phone(val serviceConnected: Boolean, val locked: Boolean, val androidAutoSettings: Boolean)

    fun refusal(phone: Phone): ServerResult? = when {
        !phone.serviceConnected -> ServerResult.SERVICE_OFF
        phone.locked -> ServerResult.LOCKED
        !phone.androidAutoSettings -> ServerResult.NOT_INSTALLED
        else -> null
    }
}

/**
 * When, during one projection, the server is switched on, restarted or off: told each state of
 * the link to Android Auto, it answers what to do. One try to start per switching on (the first dial that finds the
 * port closed), one restart (the link says Android Auto accepts and does not answer), and, when the projection ends,
 * a stop only if the server was seen up. Whether a try may really be made is [ServerGate]'s. Each projection has its
 * number ([started]); what an older one says while it ends is not the newer one's.
 */
internal class ServerPolicy {
    enum class Act { NOTHING, START, VISIT, START_AGAIN, RESTART, ANSWERED }

    private var triedStart = false
    private var triedVisit = false
    private var triedAgain = false
    private var triedRestart = false
    private var closed = false

    // The link's last word is UNRESPONSIVE: the server accepts and stays mute, and nothing has answered since.
    private var mute = false
    private var seenUp = false
    private var live = false
    private var generation = 0

    /** The projection switched on (Android Auto's side started): a new try of each. Returns this projection's number. */
    fun started(): Int {
        generation++
        live = true
        triedStart = false
        triedVisit = false
        triedAgain = false
        triedRestart = false
        closed = false
        mute = false
        seenUp = false
        return generation
    }

    /**
     * "Start" was touched in projection [of] and, after the walk's hold and the 5 s wait, the port is still closed: a visit to
     * Android Auto's settings, once per projection; if the port is still closed after it (asked again then), one more walk, once.
     * Before the visit came the one more walk: in the 19/10/2026 logs what brought the server up was exactly a second walk (the menu then
     * showed "stop", the restart stopped it, waited 1.5 s and started it). Never for an older projection, one that has
     * ended, or a link that is no longer closed (it answered, or a session came up meanwhile).
     */
    fun portNotUp(of: Int = generation): Act {
        if (!(of == generation && live && closed)) return Act.NOTHING
        return when {
            // A visit first: opening Android Auto's settings and touching nothing brought the server up in 5 of 7 cases, against
            // 2 of 6 for a second stop-and-start (logs of 19 and 20/10/2026).
            !triedVisit -> Act.VISIT.also { triedVisit = true }
            !triedAgain -> Act.START_AGAIN.also { triedAgain = true }
            else -> Act.NOTHING
        }
    }

    /**
     * Whether the link says the server is answering right now: it was seen up in this projection, its port is not closed, and it is not
     * the one that accepts and stays mute (that one has to be restarted). The walk that finds "stop" in the menu on such a server leaves
     * it alone (a server answering from before the walk began, which [PortWait] does not hear: 20:01 on 20/10/2026).
     */
    fun answeringNow(): Boolean = live && seenUp && !closed && !mute

    /** Whether the newest projection has seen the server up: its end will switch it off (unless it ends first). */
    fun serverSeenUp(): Boolean = seenUp

    /** Whether [of] is the newest projection. */
    fun isCurrent(of: Int): Boolean = of == generation

    /** A state of the link of projection [of]; one of an older projection (still ending) changes nothing. */
    fun state(state: AaLinkState, of: Int = generation): Act = if (of != generation) Act.NOTHING else when (state) {
        AaLinkState.SERVER_OFF -> {
            closed = true
            mute = false
            if (triedStart) Act.NOTHING else Act.START.also { triedStart = true }
        }
        AaLinkState.UNRESPONSIVE -> {
            seenUp = true
            mute = true
            if (triedRestart) Act.NOTHING else Act.RESTART.also { triedRestart = true }
        }
        // Still closing the session REau's own stop ended (~70 s): it clears by itself, as the screen says. Stopping and
        // starting the server inside that window is what made Android Auto fall over with "Already connected" (seen in
        // use), so it is a passive wait: the restart is not spent. If the window ends with the server still mute,
        // the link says UNRESPONSIVE and the one restart happens then; if the port closes, SERVER_OFF starts it.
        AaLinkState.CLOSING_PREVIOUS -> Act.NOTHING.also { seenUp = true; mute = false }
        // A dial that went through after the port was closed: the server came up.
        AaLinkState.CONNECTING -> (if (closed) Act.ANSWERED.also { closed = false; seenUp = true } else Act.NOTHING).also { mute = false }
        AaLinkState.ACTIVE, AaLinkState.DROPPED -> Act.NOTHING.also { seenUp = true; closed = false; mute = false }
        AaLinkState.IDLE, AaLinkState.NOT_CALLING -> Act.NOTHING.also { mute = false }
    }

    /**
     * Projection [of] ended: whether the server is to be switched off (only one seen up, never one that never
     * answered). Never when a newer projection already began: turned off and on again in under
     * 2 s, the old one's end comes after the new one's start, and the server is the new one's.
     */
    fun stopped(of: Int = generation): Boolean {
        if (of != generation) return false
        live = false
        return seenUp.also { seenUp = false }
    }

    /**
     * Whether the accessibility service that has just connected should bring the server's switching on back at once: the
     * projection goes on, the last try was refused for want of that very service ([last]), and the link still says what
     * that try was for: the port is closed (the start), or the server accepts and stays mute (the restart, whose one
     * try per projection the refusal had spent). Then the rider need not touch "Tentar de novo". Never over a link that
     * has answered since (ACTIVE, DROPPED...): a restart there would drop a session that is working.
     */
    fun retryOnServiceConnected(last: ServerResult): Boolean = live && (closed || mute) && last == ServerResult.SERVICE_OFF
}

/**
 * Who has to restart Android Auto's head unit server when it accepts and stays mute ([AaLinkState.UNRESPONSIVE]), and so
 * which text the rider is given: the app itself (the full edition with the automatic switch on and the accessibility
 * service connected, [byRider] false) or the rider, in the ⋮ menu of Android Auto (the Lite edition, which has no
 * service, or the full one while the switch is off or the service not connected).
 */
internal object MuteServerFix {
    fun byRider(hasAccessibility: Boolean, auto: Boolean, serviceConnected: Boolean): Boolean =
        !(hasAccessibility && auto && serviceConnected)
}

/**
 * Whether the port answered after "start" was touched. The receiver keeps dialling on its own pace while
 * the walk goes back, so the answer may come before the walk has ended (observed on a device): kept,
 * it counts when the walk ends, instead of the wait running out over a server that is up.
 */
internal class PortWait {
    private var walking = false
    private var answeredInWalk = false
    private var since = 0L

    // When the touched "start" of the wait that ran out was touched (0: no wait ran out and is still open to a late answer).
    private var lateSince = 0L

    /** How the port answered: [tookMs] after the touched "start", and whether it was [late] (the wait had already run out). */
    data class Answer(val tookMs: Long, val late: Boolean)

    /** The port answered since the walk began: the walk's hold after "start" ends on it. */
    val up: Boolean get() = answeredInWalk

    /** The server was already answering when the walk began (the link said so): the walk's own wait for the port has nothing to wait for. */
    fun answeringAlready() {
        answeredInWalk = true
    }

    /** A walk to start the server began. */
    fun walkBegun() {
        walking = true
        answeredInWalk = false
        lateSince = 0  // a new walk is the one the port's answer belongs to now
    }

    /** The walk ended without touching "start" (what it heard is dropped by the next [walkBegun]). */
    fun walkEnded() {
        walking = false
    }

    /**
     * The walk ended having touched "start", at [nowMs]: true if the port already answered during it; otherwise the
     * wait begins, and [answered] or [expired] ends it.
     */
    fun tapped(nowMs: Long): Boolean {
        val already = answeredInWalk
        walkEnded()
        since = if (already) 0 else nowMs
        lateSince = 0
        return already
    }

    /**
     * The port answered at [nowMs]: how long after the touched "start" it took, and whether the wait had already run out
     * (the server was only slow, and its answer is the one the screen and the log must follow); null when nothing waited
     * for it (an answer during a walk is kept for the walk's end).
     */
    fun answered(nowMs: Long): Answer? {
        if (since != 0L) return Answer(nowMs - since, late = false).also { since = 0 }
        if (lateSince != 0L && !walking) return Answer(nowMs - lateSince, late = true).also { lateSince = 0 }
        if (walking) answeredInWalk = true
        return null
    }

    /** The wait that began at [begunMs] ran out: true if it was still waiting (the server did not come up). */
    fun expired(begunMs: Long): Boolean = (since != 0L && since == begunMs).also {
        if (it) {
            since = 0
            lateSince = begunMs
        }
    }
}

/**
 * Whether a notification Android Auto posted is its head unit server's, whose one
 * action is the "Stop" that switches the server off without opening a screen. Strict, because Android Auto posts its
 * wireless and permission notices on the same channel.
 */
internal object ServerNotification {
    const val CHANNEL = "gearhead_connection_status"

    data class Seen(
        val channel: String?,
        val actions: Int,
        val creator: String?,
        /** Whether the action starts a service (API 31+); null below, where it is not known. */
        val isService: Boolean?,
        val actionTitle: String?,
        val text: String?,
    )

    fun isServerStop(seen: Seen, texts: ServerTexts): Boolean =
        seen.channel == CHANNEL &&
            seen.actions == 1 &&
            seen.creator == AndroidAuto.PACKAGE &&
            seen.isService != false &&
            (texts.isNotificationStop(seen.actionTitle) || texts.isNotificationText(seen.text))
}
