package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.util.MonotonicClock

/**
 * The name of the song a tap changed to, for the dash's title ([ActionEcho.SONG_SHOW_MS]). A tap that changes the song first says its action's name
 * ("Next song"); the new song's metadata only comes from Android Auto a little later. So the tap [asked] opens a window of [waitMs]: the first song that
 * arrives in it and is not the one that was playing at the tap is the title ([onSong]), once. A song that comes outside the window (the song
 * ended by itself, the rider changed it on the phone) never takes the title, and neither does the same song again (Android Auto repeats its
 * metadata with every change of state). The time is a parameter so a test moves it by hand; [DashSession] does the showing.
 */
class SongEcho(
    private val waitMs: Long = ActionEcho.SONG_WAIT_MS,
    private val nowMs: () -> Long = MonotonicClock::nowMs,
) {
    private var untilMs = NONE
    private var before: String? = null

    /** A key that changes the song was sent; [playing] is the song the card says now (null when none), the one the change is told from. */
    @Synchronized
    fun asked(playing: String?) {
        untilMs = nowMs() + waitMs
        before = playing
    }

    /**
     * What the title says for [song] arriving now, or null when it is not the tap's: no tap waiting, the wait over, the same song as at the tap, or a name that
     * says nothing the dash can draw. The first one that fits ends the wait.
     */
    @Synchronized
    fun onSong(song: String?): String? {
        if (untilMs == NONE) return null
        if (nowMs() >= untilMs) {
            untilMs = NONE
            return null
        }
        if (song == before) return null
        val title = titleOf(song) ?: return null
        untilMs = NONE
        return title
    }

    /** How long the wait has still to go; 0 when no tap is waiting. The action's name is held in the title for this long ([DashSession.echoSongAction]). */
    @Synchronized
    fun remainingMs(): Long = if (untilMs == NONE) 0L else (untilMs - nowMs()).coerceAtLeast(0L)

    /** Whether a tap is waiting for its song (for the tests and the log). */
    @Synchronized
    fun waiting(): Boolean = untilMs != NONE && nowMs() < untilMs

    /** Forgets a wait (the session ends, or a call begins). */
    @Synchronized
    fun cancel() {
        untilMs = NONE
        before = null
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /**
         * [song] as the title holds it: trimmed, in the letters the dash reads and cut at [ActionEcho.LABEL_MAX] characters (what the street field holds), the way
         * [DashCommands.latin1] writes it; null for nothing to say: blank, or only letters the dash cannot draw (a title in another script would show as
         * question marks, no use as a name).
         */
        fun titleOf(song: String?): String? {
            val text = DashCommands.legible(song?.trim().orEmpty()).take(ActionEcho.LABEL_MAX).trim()
            return text.takeIf { t -> t.any { it != '?' && !it.isWhitespace() } }
        }
    }
}
