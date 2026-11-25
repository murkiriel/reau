package com.reau.app.dash

import com.reau.app.phone.VolumeChange
import com.reau.app.phone.VolumeStream

/** What the volume card is about: the phone's music, or its call (a headset's call included). */
enum class VolumeKind { MUSIC, CALL }

/** What the card on the dash's video says: the [kind]'s level now out of its [max] steps. At level 0 the card says "muted". */
data class VolumeCard(val kind: VolumeKind, val level: Int, val max: Int) {
    val muted: Boolean get() = level <= 0

    /** How much of the card's bar is filled, 0 to 1. */
    val fraction: Float get() = if (max <= 0) 0f else (level.toFloat() / max).coerceIn(0f, 1f)
}

/** The card and its words ([text], in the rider's language: "Música 7/15"), what the compositor draws; the same pair again is not drawn again. */
data class VolumeNotice(val card: VolumeCard, val text: String)

/**
 * The volume card's decisions, with no Android class in them so the unit tests see them (the receiver that hears the phone's volume is
 * [com.reau.app.service.VolumeWatch]). The card is the REau's own notice of the phone's volume, drawn on the dash's video
 * ([com.reau.app.video.VolumeCardInk]) whenever the music's or the call's volume changes, whoever changed it: the phone's buttons, a Bluetooth
 * intercom, the joystick's volume action. Time and replacement of one card by the next are [TimedShow]'s.
 */
object VolumeCards {
    /** How long the card stays after the last step. */
    const val SHOW_MS = 2_000L

    /**
     * How long a card stays when a step comes with [lockLeftMs] still to go of the joystick's volume lock ([com.reau.app.aa.VolumeLock]): until the lock's end (the card
     * and the lock end together, the joystick's step having just renewed it), and never less than [SHOW_MS] (a step from outside, late in the lock, still has its 2 s,
     * and never shortens a card that the lock holds). 2 s with no lock.
     */
    fun showMs(lockLeftMs: Long): Long = maxOf(SHOW_MS, lockLeftMs)

    /**
     * The system's broadcast of a volume change and its extras. The constants are hidden from the SDK (AudioManager.VOLUME_CHANGED_ACTION and the
     * EXTRA_* beside it), so the strings are written out; they have been the same since the first Android versions and apps have long listened to them.
     */
    const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    const val EXTRA_PREVIOUS_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

    /** The streams worth a card (AudioManager's numbers): the call's, the music's and the Bluetooth headset's call (SCO). */
    const val STREAM_VOICE_CALL = 0
    const val STREAM_MUSIC = 3
    const val STREAM_BLUETOOTH_SCO = 6

    /** What a step of the volume is: the [card] to show, the level it came [from], and whether the sound is the headset's (a Bluetooth call). */
    data class Step(val card: VolumeCard, val from: Int, val headset: Boolean) {
        /** The ride log's line: no song, no name, no number of a contact. */
        fun logLine(): String {
            val kind = card.kind.name.lowercase()
            return "volume card: $kind ${card.level}/${card.max} (was $from${if (headset) ", headset" else ""})"
        }
    }

    /**
     * The step the broadcast of [stream] says ([value] now, [previous] before, [max] the stream's most), or null when it is not one for the card: another
     * stream (ring, alarm, notification), no level, a stream with no steps, or a level that did not move.
     */
    fun decide(stream: Int, value: Int, previous: Int, max: Int): Step? {
        val kind = when (stream) {
            STREAM_MUSIC -> VolumeKind.MUSIC
            STREAM_VOICE_CALL, STREAM_BLUETOOTH_SCO -> VolumeKind.CALL
            else -> null
        }
        val moved = value >= 0 && value != previous
        if (kind == null || !moved || max <= 0) return null
        return Step(VolumeCard(kind, value.coerceAtMost(max), max), previous, headset = stream == STREAM_BLUETOOTH_SCO)
    }

    /** The step of the joystick's own volume action ([change]: the stream's level before and after it, and its most), for the card to show at once. */
    fun stepOf(change: VolumeChange): Step = Step(
        VolumeCard(
            if (change.stream == VolumeStream.CALL) VolumeKind.CALL else VolumeKind.MUSIC,
            change.after.coerceIn(0, change.max.coerceAtLeast(0)), change.max,
        ),
        change.before, headset = false,
    )

    /**
     * The card for a step of the call's volume that moved nothing ([change]: the level was at the end of its range, the most going up or the least going down): the
     * system sends no broadcast for it, so the card would not show; the rider's tap is answered with the card again ("Call 8/8"). Null for a step that moved (the broadcast
     * says it) and for the music's.
     */
    fun atTheEnd(change: VolumeChange): Step? =
        if (change.stream == VolumeStream.CALL && change.before == change.after) stepOf(change) else null

    /**
     * What the title of the dash's gold band says after a volume step of the joystick: [echo] (the level, "Volume 12/30") with the card off, nothing with
     * it on, as the card says the level instead.
     */
    fun titleEcho(cardOn: Boolean, echo: () -> String): String? = if (cardOn) null else echo()
}
