package com.reau.app.phone

import android.content.Context
import android.media.AudioManager

/** The two volumes the joystick's volume actions move: the music's and the call's. */
enum class VolumeStream { MUSIC, CALL }

/**
 * What the volume actions need of the phone's audio, as little as they can: the real one is [AudioVolume]; a test puts a hand-made one in
 * its place (no AudioManager in a JVM test).
 */
interface VolumeDevice {
    /** The stream's step now, 0 to [max]. */
    fun level(stream: VolumeStream): Int

    /** The most steps the stream has; the phones differ (15 for the music on some, 25 or 30 on others). */
    fun max(stream: VolumeStream): Int

    /** One step up ([step] above 0) or down, with nothing drawn on the screen: the rider is looking at the dash, not at the phone. */
    fun adjust(stream: VolumeStream, step: Int)

    /** The phone's audio mode is a call's: a phone call, or a voice app's (WhatsApp, say), which the call state of the joystick does not see. */
    fun inCallMode(): Boolean
}

/** What one volume step did: the [stream] moved, its level [before] and [after] it, and the [max] of the stream. */
data class VolumeChange(val stream: VolumeStream, val before: Int, val after: Int, val max: Int) {
    /** The ride log's words for a step of [step] (1 or -1); no song and no name in it. */
    fun logLine(step: Int): String {
        val line = "joystick volume ${if (step > 0) "up" else "down"}: ${stream.name.lowercase()} $before -> $after of $max"
        return if (before == after) "$line (the end, no change)" else line
    }
}

/**
 * The joystick's volume actions ([com.reau.app.aa.JoystickBindings.Action.VOLUME_UP], [com.reau.app.aa.JoystickBindings.Action.VOLUME_DOWN]). The sound plays on the phone
 * (REau is the head unit and runs on it), so the step is the phone's own, straight on the stream, as the volume keys would do. During a call it is the call's volume,
 * which a Bluetooth headset follows; otherwise the music's. Nothing else is touched, and the system's own volume panel is not shown.
 */
class PhoneVolume(private val device: VolumeDevice) {
    /**
     * One step of [step] (1 up, -1 down) on the stream the moment calls for ([streamFor], with the joystick's [call] state), and what it did; null when
     * the phone refused (the log says that, the caller does not).
     */
    fun step(step: Int, call: CallState): VolumeChange? = runCatching {
        val stream = streamFor(call, device.inCallMode())
        val before = device.level(stream)
        device.adjust(stream, if (step > 0) 1 else -1)
        VolumeChange(stream, before, device.level(stream), device.max(stream))
    }.getOrNull()

    companion object {
        /**
         * The stream a step moves: the call's when the joystick sees a call going on ([CallState.IN_CALL], which needs its switch and permissions) or the
         * phone's audio mode says a call is on (a voice app's call too); the music's otherwise, ringing included (the ringer is not either of them).
         */
        fun streamFor(call: CallState, callMode: Boolean): VolumeStream =
            if (call == CallState.IN_CALL || callMode) VolumeStream.CALL else VolumeStream.MUSIC
    }
}

/** [VolumeDevice] on the phone's AudioManager: no permission needed, and flag 0 so the system draws no volume panel and plays no click. */
class AudioVolume(context: Context) : VolumeDevice {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    private fun id(stream: VolumeStream) = if (stream == VolumeStream.CALL) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    override fun level(stream: VolumeStream): Int = audio.getStreamVolume(id(stream))

    override fun max(stream: VolumeStream): Int = audio.getStreamMaxVolume(id(stream))

    override fun adjust(stream: VolumeStream, step: Int) =
        audio.adjustStreamVolume(id(stream), if (step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)

    override fun inCallMode(): Boolean = audio.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
}
