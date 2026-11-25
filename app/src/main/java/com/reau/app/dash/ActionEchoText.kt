package com.reau.app.dash

import android.content.Context
import com.reau.app.R
import com.reau.app.phone.VolumeChange
import com.reau.app.phone.VolumeStream

/**
 * The words of the labels ([EchoLabel]), in the language of the app, in one place: the echo in the dash's title and the joystick's legend on
 * its video say the same short names. Each is at most [ActionEcho.LABEL_MAX] characters and in Latin-1, as the dash reads names
 * (ActionEchoTest looks at all of them in the three languages).
 */
object ActionEchoText {
    fun text(context: Context, label: EchoLabel): String = context.getString(label.res)

    /** What the volume card on the video says: "Music 7/15", "Call 4/7", "Muted 0/15" (the level out of the stream's most; level 0 is muted whichever the stream). */
    fun volumeCard(context: Context, card: VolumeCard): String = context.getString(
        when {
            card.muted -> R.string.volume_card_muted
            card.kind == VolumeKind.CALL -> R.string.volume_card_call
            else -> R.string.volume_card_music
        },
        card.level, card.max,
    )

    /** The words of the call card's small line, in the rider's language ([CallCards.spans]): the hints, the time of the call, the end. */
    fun callWords(context: Context): CallWords = CallWords(
        answer = context.getString(R.string.call_card_answer),
        decline = context.getString(R.string.call_card_decline),
        onCall = { time -> context.getString(R.string.call_card_on_call, time) },
        end = context.getString(R.string.call_card_end),
        calling = context.getString(R.string.call_card_calling),
        ended = { time -> context.getString(R.string.call_card_ended, time) },
        missed = context.getString(R.string.call_card_missed),
    )

    /** What the title says after a volume tap: the level now out of the stream's most ("Volume 12/30"; "Call 5/7" on a call's stream). Not an [EchoLabel]: it has numbers. */
    fun volume(context: Context, change: VolumeChange): String =
        context.getString(if (change.stream == VolumeStream.CALL) R.string.volume_echo_call else R.string.volume_echo_level, change.after, change.max)
}
