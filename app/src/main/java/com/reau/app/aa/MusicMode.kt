package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Slot

/**
 * The joystick's music mode: with Spotify on the screen and playing, the media mode's left, right and centre are previous song, next song
 * and play/pause, whatever the rider's page says for them ([JoystickMap]); up and down stay the page's, and the map mode never changes.
 *
 * It is on while Spotify is in front ([spotifyInFront]) and plays. When Spotify stops playing (a pause) it stays on for [PAUSED_MS], so the
 * centre can start the music again; after that, or the moment Spotify leaves the screen, it is off and the buttons are the page's again. If
 * the music plays again within [PAUSED_MS] it never went off.
 *
 * Pure: the clock is the caller's ([nowMs] is the same monotonic clock the joystick's presses use). The projection feeds it with
 * [update] at every change of the screen, the app or the media status, so the moment the music stopped is the moment the status said so, and
 * asks [active] at every press. Each switch on or off is said once through [say] for the ride log, with the reason, never the song.
 */
class MusicMode(private val say: (String) -> Unit = {}) {
    private var on = false
    private var pausedAtMs = NEVER
    private var waitingForPicture = false

    /** What is known now: Spotify in front ([front]) and the music playing ([playing]), as of [nowMs]. */
    @Synchronized
    fun update(front: Boolean, playing: Boolean, nowMs: Long) {
        expire(nowMs)
        when {
            front && playing -> {
                pausedAtMs = NEVER
                turn(true, "Spotify playing")
            }
            !front -> {
                pausedAtMs = NEVER
                turn(false, "Spotify left the screen")
            }
            // Spotify in front and not playing: a pause when the mode was on (it counts from here); nothing when it was off.
            on && pausedAtMs == NEVER -> pausedAtMs = nowMs
        }
    }

    /**
     * [update] with what the watches say: the screen, the app in front and the media status. [appsPending]: the apps' screen has not been confirmed by the
     * picture since a select ([AaScreenWatch.appsPending]); Spotify is then in front only when the picture says so ([spotifyInFront]), and the wait is said once to the log.
     */
    @Synchronized
    fun update(screen: AaScreen, app: AaApp, media: MediaNow, nowMs: Long, appsPending: Boolean = false) {
        val front = spotifyInFront(screen, app, media, appsPending)
        update(front, media.playing, nowMs)
        val waiting = appsPending && !front && spotifyInFront(screen, app, media, appsPending = false)
        if (waiting && !waitingForPicture) say("music mode waits for the picture: Spotify plays, the app that opened is not known yet")
        waitingForPicture = waiting
    }

    /** Whether the music mode is on at [nowMs]; a pause older than [PAUSED_MS] has ended it. */
    @Synchronized
    fun active(nowMs: Long): Boolean {
        expire(nowMs)
        return on
    }

    /**
     * Whether Spotify is in front and playing right now at [nowMs]: the mode is on and no pause is counting (the grace after a pause is not playing).
     * What the dash's music card asks ([com.reau.app.dash.DashSession.musicModePlaying]): the card is the song's and its buttons are the song's here.
     */
    @Synchronized
    fun playing(nowMs: Long): Boolean {
        expire(nowMs)
        return on && pausedAtMs == NEVER
    }

    /** When the pause in course ends the mode, or null when no pause is counting. */
    @Synchronized
    fun expiresAtMs(): Long? = if (on && pausedAtMs != NEVER) pausedAtMs + PAUSED_MS else null

    private fun expire(nowMs: Long) {
        if (on && pausedAtMs != NEVER && nowMs - pausedAtMs >= PAUSED_MS) {
            pausedAtMs = NEVER
            turn(false, "Spotify paused ${PAUSED_MS / MS_PER_S} s ago")
        }
    }

    private fun turn(value: Boolean, why: String) {
        if (value == on) return
        on = value
        say("music mode ${if (value) "on" else "off"}: $why")
    }

    companion object {
        /** After a pause the music controls stay this long, for the centre to play again. */
        const val PAUSED_MS = 5_000L
        private const val NEVER = Long.MIN_VALUE
        private const val MS_PER_S = 1_000L
    }
}

/**
 * The dash's right side sometimes comes just before the centre (the ghost seen in the rides: always under 250 ms before, and mostly the
 * right). So the actions a stray right would make expensive or hard to undo show a warning on the joystick page. Pure: [warns] is the rule.
 */
object GhostSideWarning {
    /**
     * The actions that cost something when they fire by mistake.
     */
    val ACTIONS: Set<Action> = setOf(
        Action.SWAP_MAP_MUSIC, Action.OPEN_NAVIGATION, Action.OPEN_MUSIC, Action.ASSISTANT, Action.NEXT_SONG, Action.PREVIOUS_SONG,
    )

    /** Whether the page says "this side sometimes fires together with the centre" under [action] picked for [slot]: the right, in any mode and gesture. */
    fun warns(slot: Slot, action: Action): Boolean = slot.control == Control.RIGHT && action in ACTIONS
}
