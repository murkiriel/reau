package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.ActionLegend
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import java.util.Locale

/** What a dash button becomes: keys and wheel steps for Android Auto, or a call answered or ended on the phone. */
sealed class AaCommand {
    data class Key(val keycode: Int) : AaCommand()
    data class Scroll(val delta: Int) : AaCommand()
    /** Several, in order. */
    data class Steps(val commands: List<AaCommand>) : AaCommand()
    /** A finger from the phone's "Tela do Android Auto", at a point of the video. */
    data class Touch(val action: PhoneTouch.Action, val x: Int, val y: Int) : AaCommand()
    /**
     * The map or the music, whichever is not in front ([SwapMapMusic]): the screen is only known at the moment of the tap, so LiveSides turns
     * it into the media key or the navigation key then. Never reaches Android Auto as it is.
     */
    data object SwapMapMusic : AaCommand()
    /** Not for Android Auto: the phone answers the ringing call ([com.reau.app.phone.PhoneCalls]). */
    data object AnswerCall : AaCommand()
    /** Not for Android Auto: the phone rejects the ringing call or ends the one going on. */
    data object EndCall : AaCommand()
    /** Not for Android Auto: the phone's call volume one [step] up (1) or down (-1), as the dash's own codes ask while a call goes on. */
    data class CallVolume(val step: Int) : AaCommand()
    /**
     * Not for Android Auto: the phone's own volume one [step] up (1) or down (-1), the music's, or the call's while a call goes on
     * ([com.reau.app.phone.PhoneVolume]); what the page's volume actions ([Action.VOLUME_UP], [Action.VOLUME_DOWN]) send. The audio plays on the phone,
     * so there is nothing for Android Auto to do. [CallVolume] is the dash's own codes while a call goes on, a different tap.
     */
    data class Volume(val step: Int) : AaCommand()
    /** Not for Android Auto: the picture sent to the dash one [step] nearer (1) or farther (-1), the bottom edge kept ([com.reau.app.video.LiveZoom]). */
    data class PanelZoom(val step: Int) : AaCommand()
    /** Not for Android Auto: takes back the last [PanelZoom] step, if it changed the picture (a side that came with a press of the centre). */
    data object PanelZoomUndo : AaCommand()
}

/**
 * The dash's joystick through the rider's page ([JoystickBindings]).
 *
 * Which control: the dash tells its mode by the codes it sends. In its media mode the sides come as next and previous
 * track (0x09 right, 0x0A left), up and down as volume (0x06, 0x07) and the middle as play/pause (0x05); in its map
 * mode the sides come as zoom (0x13 right, 0x14 left: a capture on the bike and better-dash), the middle as
 * IN (0x18) and down as 0x15 (better-dash reads it so; proven on the bike on 28/10/2026, in the ride log). 0x0B comes once as
 * the dash connects and is no button: ignored. Back (0x12) is not on the page: back, and twice within
 * [DOUBLE_BACK_MS] home, as before.
 *
 * One tap, one action, at once (a double tap does not work well on the dash's joystick). "Held" never fires (as far
 * as is known, one code per press, no release).
 *
 * The calls use the same sides, above the page, one tap: while [callState] says
 * the phone rings, left answers and right rejects; on a call, right ends it and left does nothing (the
 * sides of Android Auto's call card); while it rings the middle rejects too; up and down go through the page. While the phone rings the dash
 * shows its own call screen, and the first tap on a side comes as 0x02 (left) or 0x01 (right), as seen on a real dash;
 * the dash then goes back to its map, so a tap after it comes as the map's side. On a call the dash keeps the sides
 * to itself and sends its middle as 0x16, which ends the call as the right does. For
 * [CALL_QUIET_MS] after a call was handled the control that did it sends nothing, so the same tap again neither ends
 * the call just answered nor moves the focus; another control counts at once (the middle ending a call just answered
 * was held back in use). [callState] is always idle unless the rider switched
 * answering on.
 *
 * The music mode ([MusicMode], [musicActive]): with Spotify on the screen and playing, the media mode's left, right and centre are previous song,
 * next song and play/pause (a call, as always, comes first). A side goes [HELD_SIDE_MS] after the tap, through [release], and is dropped if the
 * media centre comes meanwhile (the dash's ghost: a side just before the centre); the centre goes at once. The map mode and up and down never change.
 */
@Suppress("TooManyFunctions") // one small function per rule of the page; the call state read per tap is two more
class JoystickMap(
    @Volatile var bindings: JoystickBindings = JoystickBindings.DEFAULT,
    private val assistant: Boolean = false,
    private val callState: () -> CallState = { CallState.IDLE },
) {
    /**
     * What a tap gave: what to send, and the ride log's words. [echo] is the name the dash's gold band says of it ([com.reau.app.dash.ActionEcho]):
     * null for a tap that did nothing (a bounce, a tap ignored or not mapped, an action of "nothing"). [legend] is the dash mode this tap is the first
     * of (the first of the projection, or one after taps of the other mode): the joystick's legend of that mode is due ([com.reau.app.dash.ActionLegend]).
     */
    data class Press(val sent: List<AaCommand>, val lines: List<String>, val echo: EchoLabel? = null, val legend: Mode? = null)

    private var lastBackMs = Long.MIN_VALUE / 2
    private var quietUntilMs = Long.MIN_VALUE / 2
    // The control that answered, rejected or ended the call: only it is held back for CALL_QUIET_MS (the same tap again
    // is the accident; another control right after, such as the middle ending a call just answered, is meant).
    private var quietControl: Control? = null
    private var lastCode = -1
    // The dash mode of the last tap that had one, null before the first: the dash does not say its mode, the codes of the taps do.
    private var lastMode: Mode? = null
    private var lastCodeMs = Long.MIN_VALUE / 2
    private var lastSideMs = Long.MIN_VALUE / 2
    // The last side, its mode and what it sent, for the dash's press of the centre ([CenterUndo]).
    private val centerUndo = CenterUndo()

    /**
     * Whether the music mode is on at the given time ([MusicMode.active]); set by the projection. Off, the page is the rider's alone. On,
     * the media mode's sides and centre are the song's keys ([musicPress]).
     */
    @Volatile var musicActive: (nowMs: Long) -> Boolean = { false }

    // The music mode's side, held for HELD_SIDE_MS before it goes: a centre that comes meanwhile says it was the dash's ghost.
    private class HeldSide(val control: Control, val command: AaCommand, val pressedMs: Long)
    private var held: HeldSide? = null

    // The call state read in this press: the state is a settings load, a permission check and a call to the phone service, and a press
    // asked for it up to three times (audit of 26/10/2026); it cannot change between the first and the last in a tap.
    private var pressCall: CallState? = null

    private fun callNow(): CallState = pressCall ?: callState().also { pressCall = it }

    /** The call state of the press just handled, for its log line; read now if the press never needed it. */
    @Synchronized
    fun callForLog(): CallState = pressCall ?: callState()

    @Synchronized
    fun press(event: DashButtonEvent, nowMs: Long): Press {
        // A held side whose time came goes first, in the order it was pressed (the tick that releases it may not have run yet).
        val due = releaseHeld(nowMs, force = false) ?: return handle(event, nowMs)
        val press = handle(event, nowMs)
        return press.copy(sent = due.sent + press.sent, lines = press.lines + due.lines)
    }

    /** When the held side of the music mode is due to go, or null when none is held; the projection waits until then and calls [release]. */
    @Synchronized
    fun heldDueMs(): Long? = held?.let { it.pressedMs + HELD_SIDE_MS }

    /** The held side if its time has come at [nowMs]; null when none is held or it is not due yet. */
    @Synchronized
    fun release(nowMs: Long): Press? = releaseHeld(nowMs, force = false)

    /**
     * REau's own call card is on ([com.reau.app.dash.CallCards]): the dash is not told of the call, and its joystick works on the call by the plain codes ([callCardPress]).
     * Set by the projection with the switch; off, nothing here changes.
     */
    @Volatile var callCard: Boolean = false

    /**
     * The volume lock ([VolumeLock]): after a tap of a volume action, for [VolumeLock.VOLUME_LOCK_MS] only the volume actions are taken. Off ([VolumeLock.enabled]) until
     * the projection turns it on with the volume notice.
     */
    val volumeLock = VolumeLock()

    /**
     * The press that drops [event] under the volume lock, or null when the tap goes on: no lock, a call ringing or going on (which ends the lock, so the call's keys
     * always work), or a tap that is one of the page's volume actions. A dropped tap changes nothing in the map (no bounce, no mode, no held side) and does not renew the lock.
     */
    private fun droppedByLock(event: DashButtonEvent, nowMs: Long): Press? {
        val left = volumeLock.leftMs(nowMs)
        val place = whereOf(event)
        if (left > 0L && callNow() != CallState.IDLE) volumeLock.clear()
        val locked = left > 0L && callNow() == CallState.IDLE
        if (!locked || isVolumeTap(place)) return null
        val what = place?.let { name(it.first, it.second) } ?: if (event is DashButtonEvent.JoystickBack) "map up" else "code 0x${"%02X".format(event.code)}"
        return Press(emptyList(), listOf("volume lock: $what ignored (${"%.1f".format(Locale.ROOT, left / 1000.0)} s left)"))
    }

    /** Whether [place] is one of the page's volume actions (one tap): the only taps the volume lock takes. */
    private fun isVolumeTap(place: Pair<Mode, Control>?): Boolean = place != null && bindings[Slot(place.first, place.second, Gesture.ONCE)] in VOLUME_ACTIONS

    /**
     * Whether the volume lock would drop [event] now, as [press] does, with nothing changed (the lock is not cleared, nothing is kept). For what the dash session does
     * with a code before the press reaches the map: the info ring's switch on the down button is no volume tap, so under the lock it does not act either.
     */
    @Synchronized
    fun wouldDrop(event: DashButtonEvent, nowMs: Long): Boolean {
        if (volumeLock.leftMs(nowMs) <= 0L || callState() != CallState.IDLE) return false
        return !isVolumeTap(whereOf(event))
    }

    private fun handle(event: DashButtonEvent, nowMs: Long): Press {
        pressCall = null
        droppedByLock(event, nowMs)?.let { return it }
        val place = whereOf(event)
        // The dash's bounce (the same code again within BOUNCE_MS; a 0x05 has come
        // twice about 20 ms apart) and two codes in one gesture (a centre or up within
        // COMBO_MS after a side, and in the map mode a down too) are not taps.
        val bounce = event.code == lastCode && nowMs - lastCodeMs < BOUNCE_MS
        lastCode = event.code
        lastCodeMs = nowMs
        val callScreen = callScreenSide(event)
        val press = byCode(event, bounce) ?: callCardPress(event, place, nowMs) ?: centerUndo.pressFor(place, nowMs) ?: when {
            event is DashButtonEvent.JoystickBack -> backPress(nowMs)
            callScreen != null -> {
                if (callScreen != Control.CENTER) lastSideMs = nowMs
                callScreenPress(callScreen, nowMs) ?: Press(emptyList(), listOf("not mapped -> nothing"))
            }
            place == null -> Press(emptyList(), listOf("not mapped -> nothing"))
            comesWithSide(place, nowMs - lastSideMs) -> Press(emptyList(), listOf("right after a side, ignored"))
            // While the phone rings the dash's middle comes as its media or map middle, not from its call screen (a
            // ride's play/pause there started or stopped the music under the call): it rejects, as the right does.
            place.second == Control.CENTER && callNow() == CallState.RINGING -> callScreenPress(Control.CENTER, nowMs)
                ?: Press(emptyList(), listOf("not mapped -> nothing"))
            else -> pressOfPage(place, nowMs)
        }
        // A tap of a volume action (not the call's volume, which the phone keeps) locks the joystick for a while, unless the phone is in a call.
        if (press.sent.any { it is AaCommand.Volume } && callNow() == CallState.IDLE) volumeLock.renew(nowMs)
        return press.copy(legend = legendDue(modeOf(event, place)))
    }

    /**
     * With REau's own call card on ([callCard]) the dash is not told of the call, so it shows no call screen of its own and its joystick sends the plain codes of the map and
     * media modes. This is what they do while the phone rings or a call goes on, null for a tap that goes the usual way (the page, or the call's sides while it rings):
     * the map mode's up (the dash's back) sends nothing to Android Auto in either; on a call the sides do nothing, the middle ends the call (the usual rules against a
     * stray tap: [comesWithSide], [CALL_QUIET_MS]) and the media mode's up and down are the call's volume, whatever the page says. The map mode's down stays the page's.
     */
    private fun callCardPress(event: DashButtonEvent, place: Pair<Mode, Control>?, nowMs: Long): Press? {
        if (!callCard || callNow() == CallState.IDLE) return null
        if (event is DashButtonEvent.JoystickBack) return Press(emptyList(), listOf("map up: on a call, nothing"))
        if (place == null || callNow() != CallState.IN_CALL) return null
        val control = place.second
        return when {
            control == Control.LEFT || control == Control.RIGHT -> {
                lastSideMs = nowMs
                Press(emptyList(), listOf("${name(place.first, control)}: on a call, nothing"))
            }
            comesWithSide(place, nowMs - lastSideMs) -> Press(emptyList(), listOf("right after a side, ignored"))
            control == Control.CENTER -> callScreenPress(Control.CENTER, nowMs)
            place.first == Mode.MEDIA && control == Control.UP -> callVolume(CALL_VOLUME_UP)
            place.first == Mode.MEDIA && control == Control.DOWN -> callVolume(CALL_VOLUME_DOWN)
            else -> null
        }
    }

    /** The dash mode a tap's code tells; null for the codes that are no mode's (the dash's notice, the calls' own). The map mode's up is the dash's own back. */
    private fun modeOf(event: DashButtonEvent, place: Pair<Mode, Control>?): Mode? =
        place?.first ?: Mode.MAP.takeIf { event is DashButtonEvent.JoystickBack }

    /**
     * [mode] when its legend is due: the tap is the first of the projection or the first of a mode other than the last tap's. The mode is
     * remembered by every tap that has one, ignored or not. While the phone rings or a call goes on the sides are the call's, so no legend is due.
     */
    private fun legendDue(mode: Mode?): Mode? {
        if (mode == null) return null
        val changed = mode != lastMode
        lastMode = mode
        return mode.takeIf { changed && callNow() == CallState.IDLE }
    }

    /**
     * What each direction does in [mode] by the rider's page, in the order the legend lists them ([com.reau.app.dash.ActionLegend.ORDER]); null
     * for one that does nothing. In the map mode the up is the dash's own back (REau sends back, or home when twice) and the middle puts the dash in its
     * media mode. Read when the legend is built, so a page changed meanwhile counts.
     */
    fun legend(mode: Mode): List<Pair<Control, EchoLabel?>> = ActionLegend.ORDER.map { control ->
        control to when {
            mode == Mode.MAP && control == Control.UP -> EchoLabel.BACK
            mode == Mode.MAP && control == Control.CENTER -> EchoLabel.MEDIA_MODE
            else -> spoken(bindings[Slot(mode, control, Gesture.ONCE)])
        }
    }

    /** The label of a page action the tap really does: the assistant with no microphone given sends nothing, and the tap does nothing. The info ring's switch sends nothing either, but it acts. */
    private fun spoken(action: Action): EchoLabel? = EchoLabel.of(action)?.takeIf { action.command(assistant) != null || action == Action.INFO_RING }

    /**
     * The control of the dash's own call screen: while the phone rings 0x02 is the left and 0x01 the right; on a call
     * 0x16 is the middle (the sides do not come then, as far as observed) and 0x01 is still taken as the right.
     */
    private fun callScreenSide(event: DashButtonEvent): Control? {
        val code = (event as? DashButtonEvent.Unknown)?.code ?: return null
        val state = callNow()
        return when {
            code == CALL_SCREEN_LEFT && state == CallState.RINGING -> Control.LEFT
            code == CALL_SCREEN_RIGHT && state != CallState.IDLE -> Control.RIGHT
            code == CALL_SCREEN_MIDDLE && state == CallState.IN_CALL -> Control.CENTER
            else -> null
        }
    }

    /** A tap on the dash's call screen: the sides as the calls' sides, and on a call the middle ends it too. */
    private fun callScreenPress(control: Control, nowMs: Long): Press? = when {
        control != Control.CENTER -> callFirst(control, nowMs)
        nowMs < quietUntilMs && quietControl == Control.CENTER -> Press(emptyList(), listOf("center: a call was just handled, nothing"))
        else -> {
            quietUntilMs = nowMs + CALL_QUIET_MS
            quietControl = Control.CENTER
            Press(listOf(AaCommand.EndCall), listOf("call: center -> EndCall"), callEcho(AaCommand.EndCall))
        }
    }

    /**
     * Whether [place], [gapMs] after the last side, is that side's own gesture and not a tap: a centre or up within [COMBO_MS], and in the map
     * mode a down too. The media centre keeps [MEDIA_CENTER_COMBO_MS]: from there to [CenterUndo.UNDO_MS] the side was the ghost (it moved the
     * focus) and the centre is the rider's, so it goes on and takes the step back first ([CenterUndo.stepsBackFor]).
     */
    private fun comesWithSide(place: Pair<Mode, Control>, gapMs: Long): Boolean = when {
        place == Mode.MEDIA to Control.CENTER -> gapMs < MEDIA_CENTER_COMBO_MS
        else -> (place.second in AFTER_SIDE || (place.first == Mode.MAP && place.second == Control.DOWN)) && gapMs < COMBO_MS
    }

    /**
     * A press that is not the call's, through the page; a side is remembered ([CenterUndo]), and the media mode's centre that comes up to
     * [CenterUndo.UNDO_MS] after a side first takes back that side's step of the focus.
     */
    private fun pressOfPage(place: Pair<Mode, Control>, nowMs: Long): Press {
        val side = place.second == Control.LEFT || place.second == Control.RIGHT
        if (side) lastSideMs = nowMs
        // A centre while the music mode holds a side: that side was the dash's ghost, and goes no further.
        val dropped = if (place == Mode.MEDIA to Control.CENTER) dropHeld(nowMs) else null
        val back = centerUndo.stepsBackFor(place, nowMs)
        val press = callFirst(place.second, nowMs) ?: musicPress(place, nowMs) ?: onPage(place.first, place.second)
        if (side) centerUndo.remember(place.first, press.sent, nowMs)
        val result = if (back.isEmpty()) press else {
            Press(back + press.sent, listOf("media center right after a side: its step of the focus is taken back") + press.lines, press.echo)
        }
        return if (dropped == null) result else result.copy(lines = result.lines + dropped)
    }

    /**
     * The music mode's press: previous song, next song and play/pause on the media mode's left, right and centre, when the phone is quiet and
     * [musicActive]; null for any other press (it goes through the page). The centre goes at once. A side is held [HELD_SIDE_MS] first, the
     * dash's ghost being a side that comes up to about 200 ms before the centre ([dropHeld]); a side pressed while another is held sends the first.
     */
    @Suppress("ReturnCount", "ComplexCondition") // one guard of the four things the mode needs, then the centre and the side each their own way
    private fun musicPress(place: Pair<Mode, Control>, nowMs: Long): Press? {
        val (mode, control) = place
        if (mode != Mode.MEDIA || control !in MUSIC_CONTROLS || callNow() != CallState.IDLE || !musicActive(nowMs)) return null
        val action = when (control) {
            Control.LEFT -> Action.PREVIOUS_SONG
            Control.RIGHT -> Action.NEXT_SONG
            else -> Action.PLAY_PAUSE
        }
        val command = action.command(assistant) ?: return null
        val shown = "${name(mode, control)} once -> music mode, ${action.name.lowercase()}"
        if (control == Control.CENTER) return Press(listOf(command), listOf(shown), spoken(action))
        val earlier = releaseHeld(nowMs, force = true)
        held = HeldSide(control, command, nowMs)
        return Press(earlier?.sent.orEmpty(), listOf("$shown, after $HELD_SIDE_MS ms unless the centre comes") + earlier?.lines.orEmpty(), spoken(action))
    }

    /** The held side's command as a press, when its time has come at [nowMs] (or at once with [force]); the side is no longer held. */
    private fun releaseHeld(nowMs: Long, force: Boolean): Press? {
        val side = held ?: return null
        if (!force && nowMs - side.pressedMs < HELD_SIDE_MS) return null
        held = null
        return Press(listOf(side.command), listOf("music mode: ${side.control.name.lowercase()} sent ${nowMs - side.pressedMs} ms after the tap"))
    }

    /** The log line of the held side dropped because the centre came at [nowMs], or null when none is held. */
    private fun dropHeld(nowMs: Long): String? {
        val side = held ?: return null
        held = null
        return "music mode: ${side.control.name.lowercase()} dropped as the dash's ghost, the centre came ${nowMs - side.pressedMs} ms after it"
    }

    /** A tap through the rider's page. */
    private fun onPage(mode: Mode, control: Control): Press {
        val slot = Slot(mode, control, Gesture.ONCE)
        val action = bindings[slot]
        val command = action.command(assistant)
        // The info ring's switch sends nothing to Android Auto: the session shows or hides the ring by the code (DashSession.ringAfterPress).
        val line = "${name(mode, control)} once -> ${command ?: if (action == Action.INFO_RING) "info ring" else Action.NOTHING.name.lowercase()}"
        return Press(listOfNotNull(command), listOf(line), spoken(action))
    }

    /** What the code alone settles: the dash's notice, a bounce, and the call's volume (before any side or page). */
    private fun byCode(event: DashButtonEvent, bounce: Boolean): Press? {
        val unknown = event as? DashButtonEvent.Unknown
        return when {
            unknown?.code == DASH_NOTICE -> Press(emptyList(), listOf("the dash's notice, ignored"))
            bounce -> Press(emptyList(), listOf("bounce, ignored"))
            unknown != null && unknown.code in CALL_VOLUME && callNow() == CallState.IN_CALL -> callVolume(unknown.code)
            else -> null
        }
    }

    /** While a call goes on the dash sends 0x03 for volume up and 0x04 for down. */
    private fun callVolume(code: Int): Press {
        val step = if (code == CALL_VOLUME_UP) 1 else -1
        return Press(listOf(AaCommand.CallVolume(step)), listOf("call volume ${if (step > 0) "+1" else "-1"}"))
    }

    private fun backPress(nowMs: Long): Press {
        val key = back(nowMs)
        val home = key == AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME)
        return Press(listOf(key), listOf("back -> $key"), if (home) EchoLabel.HOME else EchoLabel.BACK)
    }

    /** The calls, above the page: a [Press] when the tap was the call's, null when it goes through the page. */
    private fun callFirst(control: Control, nowMs: Long): Press? {
        val side = control.takeIf { it == Control.LEFT || it == Control.RIGHT } ?: return null
        val state = callNow()
        val call = callAction(state, side)
        val name = side.name.lowercase()
        return when {
            nowMs < quietUntilMs && quietControl == side -> Press(emptyList(), listOf("$name: a call was just handled, nothing"))
            call != null -> {
                quietUntilMs = nowMs + CALL_QUIET_MS
                quietControl = side
                Press(listOf(call), listOf("call: $name -> $call"), callEcho(call))
            }
            state == CallState.IN_CALL -> Press(emptyList(), listOf("$name: on a call, nothing"))
            else -> null
        }
    }

    /** What the dash says of a call's tap: the left answers; the other end of a ringing phone declines, on a call it hangs up. */
    private fun callEcho(call: AaCommand): EchoLabel = when {
        call == AaCommand.AnswerCall -> EchoLabel.ANSWER_CALL
        callNow() == CallState.RINGING -> EchoLabel.REJECT_CALL
        else -> EchoLabel.END_CALL
    }

    private fun back(nowMs: Long): AaCommand {
        val double = nowMs - lastBackMs <= DOUBLE_BACK_MS
        // A pair is used once: the back after a home starts a new pair.
        lastBackMs = if (double) Long.MIN_VALUE / 2 else nowMs
        return AaCommand.Key(if (double) ServiceDiscoveryResponse.KEY_HOME else ServiceDiscoveryResponse.KEY_BACK)
    }

    companion object {
        private val VOLUME_ACTIONS = setOf(Action.VOLUME_UP, Action.VOLUME_DOWN)

        /**
         * Whether [keycode] is Android Auto's next or previous song: the moment the song is about to change, so the dash can show the new name
         * when the metadata comes ([com.reau.app.dash.SongEcho]). Seen at the sending, so the music mode's held side counts when it goes, not when it is tapped.
         */
        fun isSongKey(keycode: Int): Boolean = keycode == ServiceDiscoveryResponse.KEY_MEDIA_NEXT || keycode == ServiceDiscoveryResponse.KEY_MEDIA_PREVIOUS

        /** The ride log's line for a button (with the state of the call, to read a press made in one). */
        fun logLine(name: String, code: Int, lines: List<String>, call: String): String =
            "button $name 0x${"%02X".format(code)} -> ${lines.ifEmpty { listOf("nothing") }.joinToString("; ")} [call=$call]${codeNote(code)}"

        /**
         * A note for the log on a code REau has no use for but knows a report of: 0x20, said to come from the dash's own menu when the
         * rider removes a stop of the dash's own navigation. Never seen in REau's rides; written so that the first time it comes is noticed.
         */
        internal fun codeNote(code: Int): String =
            if (code == MENU_REMOVE_STOP) " (0x20: reported as the dash's own menu removing a stop; never seen here until now)" else ""

        private const val MENU_REMOVE_STOP = 0x20

        const val DOUBLE_BACK_MS = 700L
        /** A ride: a second tap on the right came 1.7 s after the reject and moved the focus. */
        const val CALL_QUIET_MS = 3_000L
        const val BOUNCE_MS = 60L
        /**
         * Within this a centre, up or (map mode) down after a side is the same gesture of the dash, not a second tap. It was 100 ms; a ride
         * had a 0x13 followed by a 0x15 104 ms later, and the dash's ghosts come up to about 150 ms after the side.
         */
        const val COMBO_MS = 150L

        /**
         * The media centre's own [COMBO_MS], as it was: a centre this soon after a side is the side's gesture (the side is the rider's); later, up to
         * [CenterUndo.UNDO_MS], the side is the ghost (rides: 111 to 188 ms) and the centre takes its step back.
         */
        const val MEDIA_CENTER_COMBO_MS = 100L

        /**
         * How long the music mode holds a side before it goes (a song skipped cannot be taken back). The dash's ghost, a side before the centre, came 111
         * to 202 ms before it in 33 cases; this is [CenterUndo.UNDO_MS], the longest a centre still takes a side for the ghost.
         */
        const val HELD_SIDE_MS = CenterUndo.UNDO_MS

        /** The media mode's controls that are the song's in the music mode; up and down stay the page's. */
        private val MUSIC_CONTROLS = setOf(Control.LEFT, Control.RIGHT, Control.CENTER)

        /**
         * The controls a side can bring along in the same gesture ([comesWithSide] adds down in the map mode: in the media mode 0x07 is the
         * volume down, which a rider may mean right after a side).
         */
        private val AFTER_SIDE = setOf(Control.CENTER, Control.UP)

        private const val CALL_VOLUME_UP = 0x03
        private const val CALL_VOLUME_DOWN = 0x04
        private val CALL_VOLUME = setOf(CALL_VOLUME_UP, CALL_VOLUME_DOWN)

        /** The sides of the dash's own call screen, while the phone rings. */
        private const val CALL_SCREEN_LEFT = 0x02
        private const val CALL_SCREEN_RIGHT = 0x01
        private const val CALL_SCREEN_MIDDLE = 0x16

        /** Sent once as the dash connects; no button. */
        private const val DASH_NOTICE = 0x0B

        /** Down in the dash's map mode, as better-dash reads it. */
        private const val MAP_DOWN = 0x15

        /**
         * Whether REau has a meaning for [event]'s code: every code [DashButtonEvent.fromCode] names, and the ones the page reads from an
         * unnamed one (the dash's notice, the call volume, the call screen's sides and middle, the map's down). Anything else is a code nobody
         * has explained yet (0x20 among them), which the projection's `resumo` counts.
         */
        fun knowsCode(event: DashButtonEvent): Boolean =
            event !is DashButtonEvent.Unknown || event.code == DASH_NOTICE || event.code == MAP_DOWN || event.code in CALL_VOLUME ||
                event.code == CALL_SCREEN_LEFT || event.code == CALL_SCREEN_RIGHT || event.code == CALL_SCREEN_MIDDLE

        /** Which mode and control a code is; null for what the page does not know. */
        fun whereOf(event: DashButtonEvent): Pair<Mode, Control>? = when (event) {
            is DashButtonEvent.NextTrack -> Mode.MEDIA to Control.RIGHT
            is DashButtonEvent.PrevTrack -> Mode.MEDIA to Control.LEFT
            is DashButtonEvent.VolumeUp -> Mode.MEDIA to Control.UP
            is DashButtonEvent.VolumeDown -> Mode.MEDIA to Control.DOWN
            is DashButtonEvent.PlayPause -> Mode.MEDIA to Control.CENTER
            // 0x13 is the right and 0x14 the left (a capture on the bike, better-dash).
            is DashButtonEvent.ZoomOut -> Mode.MAP to Control.RIGHT
            is DashButtonEvent.ZoomIn -> Mode.MAP to Control.LEFT
            is DashButtonEvent.JoystickIn -> Mode.MAP to Control.CENTER
            is DashButtonEvent.Unknown -> if (event.code == MAP_DOWN) Mode.MAP to Control.DOWN else null
            else -> null
        }
    }
}

/**
 * The call's sides, as Android Auto's call
 * card, green on the left and red on the right: ringing, left answers and right rejects; on a call, right ends it.
 */
private fun callAction(state: CallState, side: Control): AaCommand? = when (state) {
    CallState.RINGING -> if (side == Control.LEFT) AaCommand.AnswerCall else AaCommand.EndCall
    CallState.IN_CALL -> if (side == Control.RIGHT) AaCommand.EndCall else null
    CallState.IDLE -> null
}

/** A control as the ride log names it: `media right`. */
private fun name(mode: Mode, control: Control) = "${mode.name.lowercase()} ${control.name.lowercase()}"

/**
 * The map mode's centre, which opens the dash's media mode by itself. The dash's own press of the centre brings a right 100 to 180 ms before
 * its own code (6 of the 38 presses of the log of 22/10/2026), and that right zoomed the picture. So a centre right after a map side ([UNDO_MS])
 * takes back that side's zoom step (a song skipped or a call answered cannot be taken back, and stays).
 *
 * The media mode's centre has the same ghost: a side 111 to 188 ms before it (8 of 267 centres of the rides) moved the focus one item and the
 * centre then confirmed the wrong one. So a media centre up to [UNDO_MS] after a media side takes back that side's step of the focus
 * ([stepsBackFor]) before its own action, which still goes.
 */
internal class CenterUndo {
    private var sideAtMs = Long.MIN_VALUE / 2
    private var sideMode: Mode? = null
    private var sideSent: List<AaCommand> = emptyList()

    /** The side of [mode] that was just pressed ([sent] is what it sent). */
    fun remember(mode: Mode, sent: List<AaCommand>, nowMs: Long) {
        sideAtMs = nowMs
        sideMode = mode
        sideSent = sent
    }

    private fun forget() {
        sideAtMs = Long.MIN_VALUE / 2
        sideMode = null
        sideSent = emptyList()
    }

    /**
     * The wheel steps that put the focus back where a media side left it, to go before the press of the media's centre at [nowMs]; empty
     * when it is no media centre right after a media side, or that side moved nothing (a call, a song, a key). Once per side.
     */
    fun stepsBackFor(place: Pair<Mode, Control>, nowMs: Long): List<AaCommand> {
        if (place != Mode.MEDIA to Control.CENTER || sideMode != Mode.MEDIA || nowMs - sideAtMs > UNDO_MS) return emptyList()
        val back = sideSent.filterIsInstance<AaCommand.Scroll>().map { AaCommand.Scroll(-it.delta) }
        forget()
        return back
    }

    /** What the press of the map's centre at [nowMs] gives back, or null when it is no centre right after a map side. */
    fun pressFor(place: Pair<Mode, Control>?, nowMs: Long): JoystickMap.Press? {
        if (place != Mode.MAP to Control.CENTER || nowMs - sideAtMs > UNDO_MS) return null
        if (sideMode != Mode.MAP) return null
        val undo = sideSent.filterIsInstance<AaCommand.PanelZoom>().map { AaCommand.PanelZoomUndo }
        forget()
        val line = if (undo.isEmpty()) "map center right after a side: the side stays" else "map center right after a side: its zoom step is taken back"
        return JoystickMap.Press(undo, listOf(line))
    }

    companion object {
        /** How long after a side a press of the centre still brings it back, in the map mode and in the media mode. */
        const val UNDO_MS = 250L
    }
}
