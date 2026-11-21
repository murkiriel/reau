package com.reau.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android's Wi-Fi panel (the sliding one, `Settings.Panel.ACTION_WIFI`), where the rider switches the radio on: since Android 10 an app cannot do
 * it, so REau shows the way. The same intent serves the button on the main screen and the notification's action (which needs an Activity intent).
 */
object WifiPanel {
    /** The panel's action; the full Wi-Fi settings page is the way out of a phone with no panel. */
    const val ACTION = Settings.Panel.ACTION_WIFI

    /** The intent that opens the panel, as a task of its own (it is started from a notification or from a service as well). */
    fun intent(): Intent = Intent(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The intent of the full Wi-Fi settings page, for a phone that answers the panel's action with nothing. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the panel, or the Wi-Fi settings page when the phone has no panel. */
    fun open(context: Context) {
        // Not asked whether the panel exists first: Android 11 and later hide most of the other apps from this one, and the answer would be wrong.
        runCatching { context.startActivity(intent()) }.onFailure { runCatching { context.startActivity(settingsIntent()) } }
    }
}
