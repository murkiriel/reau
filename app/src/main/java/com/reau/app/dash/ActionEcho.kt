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
 * What a tap can say on the dash: one for each action of the joystick's page but "nothing", the calls' three, and, for the legend only, the dash's own
 * map mode up ([EXIT_NAVIGATION]: it asks, and REau sends nothing to Android Auto for it) and centre ([MEDIA_MODE]). [res] is the short text, in the three languages ([ActionEchoText]).
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
    SWAP_MAP_MUSIC(R.string.echo_swap_map_music),
    INFO_RING(R.string.echo_info_ring),
    ANSWER_CALL(R.string.echo_answer_call),
    REJECT_CALL(R.string.echo_reject_call),
    END_CALL(R.string.echo_end_call),

    /**
     * The echo of the swap, by where the tap went ([com.reau.app.aa.SwapMapMusic.Target]): the destination's name. [SWAP_MAP_MUSIC] is its name in the legend, where
     * the screen is not known yet; the title says one of these two, and the same for the two actions that open them for good ([of]).
     */
    SWAP_TO_MUSIC(R.string.echo_swap_to_music),
    SWAP_TO_MAP(R.string.echo_swap_to_map),

    /** The dash's own: the map mode's up asks "Exit navigation?" and its yes ends the navigation on the dash (shown in the legend, never echoed). */
    EXIT_NAVIGATION(R.string.echo_exit_navigation),

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
            Action.SWAP_MAP_MUSIC -> SWAP_MAP_MUSIC
            // Opened for good, so the title says the destination from the start, as the swap's does after the tap.
            Action.OPEN_NAVIGATION -> SWAP_TO_MAP
            Action.OPEN_MUSIC -> SWAP_TO_MUSIC
            Action.INFO_RING -> INFO_RING
        }
    }
}
