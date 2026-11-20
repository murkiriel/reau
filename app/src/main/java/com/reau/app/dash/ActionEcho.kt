package com.reau.app.dash

import com.reau.app.R
import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.dash.protocol.DashCommands

/**
 * The echo of a joystick tap: the short name of what the tap did, shown for [SHOW_MS] as the title of the dash's gold band (the first field of
 * the route card, [DashSession]) and then gone, the title back to what it was. The handlebar joystick is not precise (a press of the middle
 * can come as the one above), so the rider reads on the dash what the tap really did. The time and the replacement of one echo by the next are
 * [TimedShow]'s; the words are [ActionEchoText]'s, and the joystick's legend ([ActionLegend]) uses the same ones.
 */
object ActionEcho {
    /** How long the name of the action stays in the title. */
    const val SHOW_MS = 1_500L

    /**
     * How long the name of the song that was just changed to stays in the title ([SongEcho]): longer than an action's name, as a song's name
     * takes more reading, and the rider is looking at the road.
     */
    const val SONG_SHOW_MS = 3_000L

    /**
     * How long after the tap that changes the song (the key's sending) its name is waited for: Android Auto sends the new song's metadata a little later, and
     * one that comes after this is not the tap's any more (the title is not taken for a song that changed by itself).
     */
    const val SONG_WAIT_MS = 4_000L

    /** The most characters of a label: what the dash's street field holds ([DashCommands.ROAD_MAX]), the shortest of its texts. */
    const val LABEL_MAX = DashCommands.ROAD_MAX
}

/**
 * What a tap can say on the dash: one for each action of the joystick's page but "nothing", the calls' three, the dash's own back (which is
 * [BACK] once and [HOME] twice within [com.reau.app.aa.JoystickMap.DOUBLE_BACK_MS]), and, for the legend only, the map mode's centre
 * ([MEDIA_MODE]). [res] is the short text, in the three languages ([ActionEchoText]).
 */
enum class EchoLabel(val res: Int) {
    WHEEL_PREVIOUS(R.string.echo_wheel_previous),
    WHEEL_NEXT(R.string.echo_wheel_next),
    CONFIRM(R.string.echo_confirm),
    BACK(R.string.echo_back),
    HOME(R.string.echo_home),
    PLAY_PAUSE(R.string.echo_play_pause),
    NEXT_SONG(R.string.echo_next_song),
    PREVIOUS_SONG(R.string.echo_previous_song),
    VOLUME_UP(R.string.echo_volume_up),
    VOLUME_DOWN(R.string.echo_volume_down),
    ASSISTANT(R.string.echo_assistant),
    ZOOM_IN(R.string.echo_zoom_in),
    ZOOM_OUT(R.string.echo_zoom_out),
    OPEN_NAVIGATION(R.string.echo_open_navigation),
    CYCLE_APPS(R.string.echo_cycle_apps),
    OPEN_MUSIC(R.string.echo_open_music),
    OPEN_PHONE(R.string.echo_open_phone),
    FOCUS_UP(R.string.echo_focus_up),
    FOCUS_DOWN(R.string.echo_focus_down),
    FOCUS_LEFT(R.string.echo_focus_left),
    FOCUS_RIGHT(R.string.echo_focus_right),
    INFO_RING(R.string.echo_info_ring),
    ANSWER_CALL(R.string.echo_answer_call),
    REJECT_CALL(R.string.echo_reject_call),
    END_CALL(R.string.echo_end_call),

    /** The dash's own: the map mode's centre puts it in its media mode (shown in the legend, never echoed). */
    MEDIA_MODE(R.string.echo_media_mode),
    ;

    /** The label of a tap that changes the song: its title is held until the new song's name comes ([DashSession.echoSongAction]). */
    val changesSong: Boolean get() = this == NEXT_SONG || this == PREVIOUS_SONG

    companion object {
        /** The label of a page action; null for "nothing", which says nothing. */
        fun of(action: Action): EchoLabel? = when (action) {
            Action.NOTHING -> null
            Action.WHEEL_PREVIOUS -> WHEEL_PREVIOUS
            Action.WHEEL_NEXT -> WHEEL_NEXT
            Action.CONFIRM -> CONFIRM
            Action.BACK -> BACK
            Action.HOME -> HOME
            Action.PLAY_PAUSE -> PLAY_PAUSE
            Action.NEXT_SONG -> NEXT_SONG
            Action.PREVIOUS_SONG -> PREVIOUS_SONG
            Action.VOLUME_UP -> VOLUME_UP
            Action.VOLUME_DOWN -> VOLUME_DOWN
            Action.ASSISTANT -> ASSISTANT
            Action.ZOOM_IN -> ZOOM_IN
            Action.ZOOM_OUT -> ZOOM_OUT
            Action.OPEN_NAVIGATION -> OPEN_NAVIGATION
            Action.CYCLE_APPS -> CYCLE_APPS
            Action.OPEN_MUSIC -> OPEN_MUSIC
            Action.OPEN_PHONE -> OPEN_PHONE
            Action.FOCUS_UP -> FOCUS_UP
            Action.FOCUS_DOWN -> FOCUS_DOWN
            Action.FOCUS_LEFT -> FOCUS_LEFT
            Action.FOCUS_RIGHT -> FOCUS_RIGHT
            Action.INFO_RING -> INFO_RING
        }
    }
}
