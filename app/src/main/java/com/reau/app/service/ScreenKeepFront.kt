package com.reau.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What "Mostrar o REau sobre o bloqueio" shares between the service and the app's own activity (the way the maker's own app does it: its navigation
 * activity is already in front, with showWhenLocked and turnScreenOn on since the ride began; the service only lights the screen and never starts an activity).
 * [armed]: the function and its lock-screen choice are on and the projection runs, so [com.reau.app.MainActivity] is to be shown over the lock screen and to turn the
 * screen on. [resumed]: whether that activity is the resumed one now, which [ScreenKeeper] reads a moment after it lights the screen to tell whether REau was in front.
 */
internal object ScreenKeepFront {
    private val armedFlow = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = armedFlow.asStateFlow()

    /** Written by the activity's onResume and onPause, on the main thread; read on it too. */
    @Volatile var resumed = false

    /**
     * The rider pressed "Apagar a tela nesta viagem": the screen is not lit again, and not armed, until the next projection ([clearPause]). The saved setting is not
     * changed. Written on the main thread, read by [ScreenKeeper] on it.
     */
    @Volatile var paused = false
        private set

    /** The service switches the choice on or off (always on the main thread); with the rider's pause it stays off. */
    fun arm(on: Boolean) {
        armedFlow.value = on && !paused
    }

    /** The rider's pause: nothing lights the screen and the app's activity is not kept over the lock screen until [clearPause]. */
    fun pauseByRider() {
        paused = true
        armedFlow.value = false
    }

    /** A projection ended (or the function went off): the next one goes back to what the setting says. */
    fun clearPause() {
        paused = false
    }
}
