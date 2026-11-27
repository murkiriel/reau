package com.reau.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ScreenLock
import kotlinx.coroutines.launch

/**
 * The Android side of the "Bloquear a tela" button (0.3.93 test build, rules in [ScreenLock]). While [active] the activity's window keeps the screen on
 * (FLAG_KEEP_SCREEN_ON: Android never locks), the system bars are hidden and [ScreenLockScreen] is drawn in front of everything, swallowing every touch; only the
 * thumb dragged to the end leaves. Nothing here asks Android to unlock, shows the activity over a lock screen or touches the PIN. It also ends when the app leaves
 * the front ([stopped]), when the screen goes off (the default display's state, with the SCREEN_OFF broadcast as a second source) and when the projection stops.
 * Back does nothing, and is logged once per mode. Every call is on the main thread.
 */
internal class ScreenLockMode(private val activity: ComponentActivity, private val keep: KeepScreenOn) {
    /** Whether the mode is on now; the screen reads it. */
    var active by mutableStateOf(false)
        private set

    private val state = ScreenLock.State()
    private val main = Handler(Looper.getMainLooper())
    private var back: OnBackPressedCallback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOff: BroadcastReceiver? = null

    /** From the activity's onPostCreate (after its setContent): the layer of the screen goes on top of the others, and the projection stopping ends the mode. */
    fun attach() {
        activity.addContentView(
            ComposeView(activity).apply { setContent { if (active) ScreenLockScreen(this@ScreenLockMode) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activity.lifecycleScope.launch {
            ProjectionService.projection.collect { if (it.phase == Phase.STOPPED) exit(ScreenLock.Exit.PROJECTION) }
        }
    }

    /** Told after the mode goes in or out (the idle count follows it). */
    var onChange: (() -> Unit)? = null

    /** In, only with the projection on: the button ([ScreenLock.Entry.BUTTON]), or the idle count running out ([ScreenLock.Entry.IDLE]). */
    fun enter(entry: ScreenLock.Entry = ScreenLock.Entry.BUTTON) {
        if (active || !ScreenLock.available(ProjectionService.projection.value.phase != Phase.STOPPED)) return
        state.enter(SystemClock.elapsedRealtime(), entry)?.let(::log)
        active = true
        keep.want(SOURCE)
        bars(hidden = true)
        // Added now, so it is the last one added: Back goes to it first, and it does nothing but say so once.
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                state.backIgnored()?.let(::log)
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        back = callback
        watchTheDisplay()
        val off = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = exit(ScreenLock.Exit.SCREENOFF)
        }
        screenOff = off
        ContextCompat.registerReceiver(activity, off, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        onChange?.invoke()
    }

    /** The activity left the front (Home, another app, the screen going off): the mode ends, as `screenoff` if the display is off, else as `left`. */
    fun stopped() {
        if (!active) return
        val off = activity.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let { it != ScreenLock.DISPLAY_ON } == true
        exit(if (off) ScreenLock.Exit.SCREENOFF else ScreenLock.Exit.LEFT)
    }

    /** Out, for [reason]: the flag, the bars, Back's callback, the display listener and the receiver go; the brightness goes back with the screen leaving the composition. */
    fun exit(reason: ScreenLock.Exit) {
        if (!active) return
        active = false
        state.exit(reason, SystemClock.elapsedRealtime())?.let(::log)
        keep.release(SOURCE)
        bars(hidden = false)
        back?.remove()
        back = null
        displayListener?.let { activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
        screenOff?.let { runCatching { activity.unregisterReceiver(it) } }
        screenOff = null
        onChange?.invoke()
    }

    /** The window's brightness: the minimum when [on], the phone's own otherwise. */
    fun dim(on: Boolean) {
        val window = activity.window
        window.attributes = window.attributes.also { it.screenBrightness = if (on) DIMMEST else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    /**
     * The default display's state decides "the screen went off": during a projection of Android Auto the phone stays "interactive" and no SCREEN_OFF broadcast comes
     * (found in the 0.3.92 test), but the display does change its state. Each change is written, and one that is not ON ends the mode.
     */
    private fun watchTheDisplay() {
        val manager = activity.getSystemService(DisplayManager::class.java) ?: return
        val watch = ScreenLock.DisplayWatch(manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: ScreenLock.DISPLAY_ON)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val change = manager.getDisplay(displayId)?.state?.let(watch::offer) ?: return
                log(change.line)
                if (change.off) exit(ScreenLock.Exit.SCREENOFF)
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, main)
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

    private fun log(line: String) = RideDiagnostics.log(ScreenLock.TAG, line)

    companion object {
        /** With no touch for this long the window's brightness goes to the minimum. */
        const val DIM_AFTER_MS = 5_000L
        private const val DIMMEST = 0.01f
        private const val SOURCE = "lock"
    }
}
