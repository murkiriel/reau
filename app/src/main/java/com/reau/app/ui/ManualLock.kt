package com.reau.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ScreenKeepManual
import com.reau.app.service.ScreenKeepPolicy
import kotlinx.coroutines.launch

/**
 * The Android side of the "Bloquear a tela" button (0.3.92 test build, rules in [ScreenKeepManual]): while [active] the activity's window keeps the screen on
 * (FLAG_KEEP_SCREEN_ON), the system bars are hidden, and [LockOverScreen] is drawn in front of everything (touches that are not a swipe up do nothing). It ends by
 * a swipe up, by Back, when the projection stops, or when the screen goes off (the power button); on the way out the flag, the bars and the brightness go back
 * to normal. It does not depend on the switch "Tela acesa com o celular bloqueado". Every call is on the main thread.
 */
internal class ManualLock(private val activity: ComponentActivity) {
    /** Whether the manual mode is on now; the screen reads it. */
    var active by mutableStateOf(false)
        private set

    private val state = ScreenKeepManual.State()
    private var back: OnBackPressedCallback? = null
    private var screenOff: BroadcastReceiver? = null

    /** From the host's attach: the projection stopping ends the mode. */
    fun attach() {
        activity.lifecycleScope.launch {
            ProjectionService.projection.collect { if (it.phase == Phase.STOPPED) exit(ScreenKeepManual.Exit.PROJECTION) }
        }
    }

    /** The button: in, only with the projection on. */
    fun enter() {
        if (active || !ScreenKeepManual.available(ProjectionService.projection.value.phase != Phase.STOPPED)) return
        state.enter(SystemClock.elapsedRealtime())?.let { RideDiagnostics.log(ScreenKeepPolicy.TAG, it) }
        active = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars(hidden = true)
        // Added now, so it is the last one added and Back goes to it before the screens' own.
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit(ScreenKeepManual.Exit.BACK)
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        back = callback
        val off = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = exit(ScreenKeepManual.Exit.SCREEN_OFF)
        }
        screenOff = off
        ContextCompat.registerReceiver(activity, off, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Out, for [reason]: the flag, the bars, Back's callback and the receiver go; the brightness goes back with the screen leaving the composition. */
    fun exit(reason: ScreenKeepManual.Exit) {
        if (!active) return
        active = false
        state.exit(reason, SystemClock.elapsedRealtime())?.let { RideDiagnostics.log(ScreenKeepPolicy.TAG, it) }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars(hidden = false)
        back?.remove()
        back = null
        screenOff?.let { runCatching { activity.unregisterReceiver(it) } }
        screenOff = null
    }

    private fun bars(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
