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

    /** What the title says after a volume tap: the level now out of the stream's most ("Volume 12/30"; "Call 5/7" on a call's stream). Not an [EchoLabel]: it has numbers. */
    fun volume(context: Context, change: VolumeChange): String =
        context.getString(if (change.stream == VolumeStream.CALL) R.string.volume_echo_call else R.string.volume_echo_level, change.after, change.max)
}
