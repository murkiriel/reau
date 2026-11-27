package com.reau.app.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.reau.app.service.IdleLock

/**
 * The window's one FLAG_KEEP_SCREEN_ON, shared by the two things that want it: REau's lock ([ScreenLockMode]) and the idle count before it ([IdleLockWatch]). The flag goes on
 * with the first that wants it and off with the last that lets go ([IdleLock.KeepSources]), so neither takes the other's away. The window is read at each call (it does not
 * exist when the activity is built). Main thread.
 */
internal class KeepScreenOn(private val activity: ComponentActivity) {
    private val sources = IdleLock.KeepSources()

    /** [source] wants the screen kept on. */
    fun want(source: String) {
        if (sources.want(source)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Whether the window has the flag now (for the log's snapshot; the flag is set and cleared only by [want] and [release]). */
    fun isSet(): Boolean = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    /** [source] lets go; the flag is cleared when it was the last. */
    fun release(source: String) {
        if (sources.release(source)) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
