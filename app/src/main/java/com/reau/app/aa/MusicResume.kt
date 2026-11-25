package com.reau.app.aa

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.reau.app.phone.CallState
import com.reau.app.phone.PhoneCalls

/**
 * The music that was playing when Android Auto closed the session because of the phone's USB ([UsbSessionEnd]) stays paused after the session
 * comes back (the audio of the old session is gone and nobody presses play). When the new session is up, this says whether to press PLAY once:
 * only when the end was the USB's, the music was playing when the session ended, no call is on, and the new session came within [validMs].
 * The clock is a parameter: elapsed realtime in the app, a counter in the tests. The decision is the whole of the class; [MusicKey] presses the key.
 */
internal class MusicResume(private val clock: () -> Long, private val validMs: Long = VALID_MS) {
    private class Pending(val endedAtMs: Long, val wasPlaying: Boolean)

    private var pending: Pending? = null

    /** What to do with the music once the new session is up, and what the log says of it ([line]). */
    enum class Verdict(val line: String) {
        RESUME("music resumed after the USB ended the Android Auto session"),
        WAS_STOPPED("music not resumed after the USB ended the Android Auto session: it was not playing"),
        IN_A_CALL("music not resumed after the USB ended the Android Auto session: a call is on"),
        PLAYING_AGAIN("music not resumed after the USB ended the Android Auto session: it plays again by itself"),
        TOO_LATE("music not resumed after the USB ended the Android Auto session: the new session took too long to come"),
    }

    /** A session that had been answered ended now: [byUsb] if the USB's change ended it, [wasPlaying] if the music was playing then. Any other end forgets a wait. */
    @Synchronized
    fun sessionEnded(byUsb: Boolean, wasPlaying: Boolean) {
        pending = if (byUsb) Pending(clock(), wasPlaying) else null
    }

    /** Whether a new session is waited for to resume the music. */
    @Synchronized
    fun waiting(): Boolean = pending != null

    /**
     * The new session is up: what to do now, null when no USB end is waiting. One wait gives one answer, ever: asked again, it says null.
     * [inCall]: a call rings or goes on; [playingNow]: Android Auto already says the music plays.
     */
    @Synchronized
    fun decide(inCall: Boolean, playingNow: Boolean): Verdict? {
        val wait = pending ?: return null
        pending = null
        return when {
            clock() - wait.endedAtMs > validMs -> Verdict.TOO_LATE
            !wait.wasPlaying -> Verdict.WAS_STOPPED
            inCall -> Verdict.IN_A_CALL
            playingNow -> Verdict.PLAYING_AGAIN
            else -> Verdict.RESUME
        }
    }

    companion object {
        /** The session comes back in 3.6 to 8.9 s after a USB end (rides of November 2026): a wait older than this is not the same fall. */
        const val VALID_MS = 60_000L

        /** How long after the new session opened the decision is taken: the music status of Android Auto has been heard by then. */
        const val AFTER_OPEN_MS = 3_000L
    }
}

/**
 * The media key PLAY, through the system's own dispatch (`AudioManager.dispatchMediaKeyEvent`): it goes to the music app that played last, with no need of Android
 * Auto. Not Android Auto's input channel: it announces only play/pause, a toggle that would pause a music that came back by itself.
 */
internal object MusicKey {
    /** Presses and releases PLAY; false when the phone refused. */
    fun play(context: Context): Boolean = runCatching {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }.isSuccess

    /** A call rings or goes on: by the phone's state, by Telecom (voice apps too) or by the audio mode. */
    fun inCall(context: Context): Boolean {
        val calls = PhoneCalls(context)
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        val mode = runCatching { audio.mode }.getOrNull()
        return calls.state() != CallState.IDLE || calls.telecomInCall() == true ||
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
