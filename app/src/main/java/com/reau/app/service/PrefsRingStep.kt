package com.reau.app.service

import android.content.Context
import androidx.core.content.edit
import com.reau.app.dash.RingCycle
import com.reau.app.dash.RingStepMemory

/** The step of the two rings' turns ([RingCycle]) in the phone's preferences, apart from the settings: a press of the dash's button changes it, not a screen. */
class PrefsRingStep(context: Context) : RingStepMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var step: Int
        get() = RingCycle.valid(prefs.getInt(KEY, 0))
        set(value) = prefs.edit { putInt(KEY, RingCycle.valid(value)) }

    private companion object {
        const val FILE = "reau_ring"
        const val KEY = "step"
    }
}
