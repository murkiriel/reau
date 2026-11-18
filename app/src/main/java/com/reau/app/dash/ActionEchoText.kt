package com.reau.app.dash

import android.content.Context

/**
 * The words of the labels ([EchoLabel]), in the language of the app, in one place: the echo in the dash's title and the joystick's legend on
 * its video say the same short names. Each is at most [ActionEcho.LABEL_MAX] characters and in Latin-1, as the dash reads names
 * (ActionEchoTest looks at all of them in the three languages).
 */
object ActionEchoText {
    fun text(context: Context, label: EchoLabel): String = context.getString(label.res)
}
