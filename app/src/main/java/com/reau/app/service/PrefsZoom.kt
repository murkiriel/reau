package com.reau.app.service

import android.content.Context
import androidx.core.content.edit
import com.reau.app.video.LiveZoom
import com.reau.app.video.ZoomMemory

/** The joystick's zoom in the phone's preferences, apart from the settings: a press of the dash's joystick changes it, not a screen. */
class PrefsZoom(context: Context) : ZoomMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var percent: Int
        get() = LiveZoom.snap(prefs.getInt(KEY, LiveZoom.MIN))
        set(value) = prefs.edit { putInt(KEY, LiveZoom.snap(value)) }

    private companion object {
        const val FILE = "reau_zoom"
        const val KEY = "percent"
    }
}
