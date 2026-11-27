package com.reau.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionService
import com.reau.app.service.ProximityLock
import com.reau.app.service.ScreenKeepPolicy
import com.reau.app.service.LockLog
import com.reau.app.service.ScreenLock
import com.reau.app.service.ScreenLockLog
import com.reau.app.service.ScreenWatch
import kotlinx.coroutines.launch

/**
 * The Android side of the "Bloquear a tela" button (0.3.93 test build, rules in [ScreenLock]). While [active] the activity's window keeps the screen on
 * (FLAG_KEEP_SCREEN_ON: Android never locks), the system bars are hidden and [ScreenLockScreen] is drawn in front of everything, swallowing every touch; only the
 * thumb dragged to the end leaves. Nothing here asks Android to unlock, shows the activity over a lock screen or touches the PIN. It also ends when the app leaves
 * the front ([stopped]), when the screen goes off (the default display's state, with the SCREEN_OFF broadcast as a second source) and when the projection stops.
 * Back does nothing, and is logged once per mode. Every call is on the main thread.
 *
 * Since 0.3.99 it also holds the proximity wake lock ([ProximityHold]) while it is on and the activity is in front: with the sensor covered (a pocket) the display goes off and
 * the phone does not lock. That off is not "the screen went off": [lookAtTheDisplay] tells the lock's own off from any other ([ProximityLock.covers]) and only a real one ends
 * the mode. The lock is let go by every way out: [exit] (all reasons), [paused] (the app leaves the front with the mode still on: back in [resumed]) and the activity's onDestroy.
 *
 * Since 0.3.99 too it writes what happens to the mode for the ride log: the brightness going down and back ([dim]), the touches on the track, the drags let go short and the one
 * that unlocked ([slideTouched], [slideAborted], [slideDone]), Back, the focus and the activity's lifecycle ([focusChanged], [resumed], [paused], [stopped]), and a summary at
 * [exit] ([ScreenLockLog]). It tells [ScreenWatch] what it hears (the display, SCREEN_OFF) and lends it its window, so the lock screen and the minute's snapshot are written
 * ([LockLog]) while the mode is on. Every failure of a registration is a warning in the log.
 */
@Suppress("TooManyFunctions") // one small function per case (the proximity lock added three); splitting them would only scatter the same thing
internal class ScreenLockMode(
    private val activity: ComponentActivity,
    private val keep: KeepScreenOn,
    /** Whether [LockOverHost]'s screen is up over the phone's lock screen: it dims the same window, so leaving the lock does not touch the brightness then. */
    private val overTheLockScreen: () -> Boolean = { false },
) {
    /** Whether the mode is on now; the screen reads it. */
    var active by mutableStateOf(false)
        private set

    private val state = ScreenLock.State()
    private var dimTrack = ScreenLockLog.DimTrack()
    private var counters = ScreenLockLog.Counters(0L, 0L, 0L)
    private val main = Handler(Looper.getMainLooper())
    private var back: OnBackPressedCallback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOff: BroadcastReceiver? = null
    private val proximity = ProximityHold(activity, main)
    private val proximityEdge = ScreenLock.ProximityEdge()

    /** The default display is not on now, since when (the clock of [SystemClock.elapsedRealtime]), as the display listener last saw it. */
    private var displayOff = false
    private var offSinceMs = 0L
    private val offCheck = Runnable { lookAtTheDisplay() }

    /** From the activity's onPostCreate (after its setContent): the layer of the screen goes on top of the others, and the projection stopping ends the mode. */
    fun attach() {
        activity.addContentView(
            ComposeView(activity).apply { setContent { if (active) ScreenLockScreen(this@ScreenLockMode) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activity.lifecycleScope.launch {
            ProjectionService.projection.collect { if (it.phase == Phase.STOPPED) exit(ScreenLock.Exit.PROJECTION) }
        }
        proximity.onSensorChange = ::lookAtTheDisplay
        // The last way out: whatever happened to the mode, a destroyed activity never keeps the lock.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacks(offCheck)
                proximity.release(ScreenLock.PROXIMITY_DESTROY)
                // A destroyed activity does not leave the state lines running with its window.
                ScreenWatch.end(ScreenWatch.SOURCE_LOCK)
            }
        })
    }

    /** Told after the mode goes in or out (the idle count follows it). */
    var onChange: (() -> Unit)? = null

    /** In, only with the projection on: the button ([ScreenLock.Entry.BUTTON]), or the idle count running out ([ScreenLock.Entry.IDLE]). */
    fun enter(entry: ScreenLock.Entry = ScreenLock.Entry.BUTTON) {
        if (active || !ScreenLock.available(ProjectionService.projection.value.phase != Phase.STOPPED)) return
        state.enter(SystemClock.elapsedRealtime(), entry)?.let(::log)
        dimTrack = ScreenLockLog.DimTrack()
        counters = ScreenLockLog.Counters(SystemClock.elapsedRealtime(), ScreenWatch.wakes, ScreenWatch.keyguardLocks)
        active = true
        keep.want(SOURCE)
        bars(hidden = true)
        // Added now, so it is the last one added: Back goes to it first, and it does nothing but say so once.
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                state.backIgnored(SystemClock.elapsedRealtime())?.let(::log)
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        back = callback
        watchTheDisplay()
        val off = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ScreenWatch.screenEvent(intent.action)
                exit(ScreenLock.Exit.SCREENOFF)
            }
        }
        screenOff = off
        runCatching { ContextCompat.registerReceiver(activity, off, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { log(LockLog.warnLine("listening to the screen going off", it)) }
        // Last, with the display listener already up: the first display change the lock causes is heard.
        if (front()) proximity.acquire(ScreenLock.PROXIMITY_ENTER)
        // The lock screen and the minute's snapshot are written from now (the snapshot reads this window).
        ScreenWatch.window = ::windowReading
        ScreenWatch.begin(activity, ScreenWatch.SOURCE_LOCK)
        onChange?.invoke()
    }

    /** REau is in front again (the activity's onResume): the mode may have stayed on through an onPause, and the lock is taken again. */
    fun resumed() {
        lifecycleSeen(ScreenLockLog.RESUMED)
        if (active) proximity.acquire(ScreenLock.PROXIMITY_RESUME)
    }

    /** The window got or lost the focus (the notification shade, a system dialog, a call); said while the mode is on, with the time it was lost when it is back. */
    fun focusChanged(hasFocus: Boolean) {
        if (active) counters.focus(hasFocus, SystemClock.elapsedRealtime())?.let(::log)
    }

    private fun lifecycleSeen(event: String) {
        if (active) log(counters.activity(event, SystemClock.elapsedRealtime()))
    }

    /**
     * REau left the front (the activity's onPause), the mode on or not: the lock goes at once with `release(0)`. Not at onStop alone: between the two the activity is still
     * visible to the system but not in front (a dialog of the system, the multi-window), and a lock held there would turn the display off under something the rider is using.
     * Mostly onStop follows and [stopped] ends the mode anyway.
     */
    fun paused() {
        lifecycleSeen(ScreenLockLog.PAUSED)
        main.removeCallbacks(offCheck)
        proximity.release(ScreenLock.PROXIMITY_PAUSE)
    }

    private fun front(): Boolean = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** The activity left the front (Home, another app, the screen going off): the mode ends, as `screenoff` if the display is off, else as `left`. */
    fun stopped() {
        if (!active) return
        lifecycleSeen(ScreenLockLog.STOPPED)
        val off = activity.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let { it != ScreenLock.DISPLAY_ON } == true
        exit(if (off) ScreenLock.Exit.SCREENOFF else ScreenLock.Exit.LEFT)
    }

    /**
     * Out, for [reason]: the flag, the bars, Back's callback, the display listener and the receiver go, and the window's brightness comes back at once (the screen's own
     * `onDispose` does it too, when the composition is let go, which may wait for the activity to be back). The window is the one [LockOverHost] dims over the lock screen,
     * so the brightness is left alone while that screen is up.
     */
    fun exit(reason: ScreenLock.Exit) {
        if (!active) return
        active = false
        // First of all, so nothing below can leave it held. Leaving by the end of the projection (maybe in a pocket) waits for the sensor to be uncovered; the others light the display.
        main.removeCallbacks(offCheck)
        proximity.release(reason.logName, waitForFar = reason == ScreenLock.Exit.PROJECTION)
        proximityEdge.on()
        counters.proximityOn(SystemClock.elapsedRealtime())
        displayOff = false
        state.exit(reason, SystemClock.elapsedRealtime())?.let(::log)
        keep.release(SOURCE)
        bars(hidden = false)
        back?.remove()
        back = null
        displayListener?.let { activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
        screenOff?.let {
            runCatching { activity.unregisterReceiver(it) }.onFailure { error -> log(LockLog.warnLine("stop listening to the screen going off", error)) }
        }
        screenOff = null
        if (!overTheLockScreen()) dim(false)
        // The way out says what the mode did, right after `manual off`; the state lines stop with it (the keeper's own go on).
        val at = SystemClock.elapsedRealtime()
        log(counters.summary(at, dimTrack.dimMs(at), state.backCount, ScreenWatch.wakes, ScreenWatch.keyguardLocks).line())
        ScreenWatch.end(ScreenWatch.SOURCE_LOCK)
        onChange?.invoke()
    }

    /** The window's brightness: the minimum when [on], the phone's own otherwise; a change is written with [reason] (null: `idle` going dim, `exit` coming back; the screen says `enter`, `touch` or `drag`). */
    fun dim(on: Boolean, reason: String? = null) {
        val window = activity.window
        window.attributes = window.attributes.also { it.screenBrightness = if (on) DIMMEST else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        dimTrack.set(on, reason, SystemClock.elapsedRealtime())?.let(::log)
    }

    /** The window as the snapshot reads it: its brightness and whether the keep-on flag is set. */
    private fun windowReading(): LockLog.WindowReading {
        val attributes = activity.window.attributes
        return LockLog.WindowReading(attributes.screenBrightness, keep.isSet())
    }

    /** A finger went down on the unlock track: counted, and said at most once in [ScreenLockLog.GROUP_MS] with the count so far. */
    fun slideTouched() {
        if (!active) return
        counters.touches.hit(SystemClock.elapsedRealtime())?.let { log(ScreenLockLog.touchLine(it)) }
    }

    /** A drag of the thumb was let go short of the end, at [progress] of the way at its farthest. */
    fun slideAborted(progress: Float) {
        if (!active) return
        counters.abort()
        log(ScreenLockLog.abortLine(ScreenLockLog.percent(progress)))
    }

    /** The drag that unlocks was let go at the end, [dragMs] after the finger went down (said before the mode's own `manual off`). */
    fun slideDone(dragMs: Long) {
        if (active) log(ScreenLockLog.doneLine(dragMs))
    }

    /**
     * The default display's state decides "the screen went off": during a projection of Android Auto the phone stays "interactive" and no SCREEN_OFF broadcast comes
     * (found in the 0.3.92 test), but the display does change its state. Each change is written, and one that is not ON ends the mode.
     */
    private fun watchTheDisplay() {
        val manager = activity.getSystemService(DisplayManager::class.java)
        if (manager == null) {
            log(LockLog.unavailableLine("display manager"))
            return
        }
        val watch = ScreenLock.DisplayWatch(manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: ScreenLock.DISPLAY_ON)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val change = manager.getDisplay(displayId)?.state?.let(watch::offer) ?: return
                // The change is written by the state lines ([ScreenWatch]: one line for it, whoever heard it first, with the phone's interactive state of the same moment).
                ScreenWatch.displayChanged()
                if (change.off) {
                    if (!displayOff) offSinceMs = SystemClock.elapsedRealtime()
                    displayOff = true
                    lookAtTheDisplay()
                } else {
                    displayOff = false
                    main.removeCallbacks(offCheck)
                    proximityEdge.on()?.let(::log)
                    counters.proximityOn(SystemClock.elapsedRealtime())
                }
            }
        }
        displayListener = listener
        runCatching { manager.registerDisplayListener(listener, main) }.onFailure { log(LockLog.warnLine("display listener", it)) }
    }

    /**
     * The display is not on: if it is the proximity lock's own off (held, interactive, the sensor covered or the other road still to come: [ProximityLock.covers]) the mode
     * stays, said once; any other off (the power button, a timeout) ends it as `screenoff`, as before 0.3.99. Looked at again when the sensor changes and, while the sensor reads
     * uncovered, after the grace the sensor's event is given to arrive. The hypothesis, to be confirmed on the phone: a proximity off leaves `isInteractive` true and sends no
     * SCREEN_OFF; a real one during a projection of Android Auto may leave it true too, so the sensor is asked as well.
     */
    private fun lookAtTheDisplay() {
        main.removeCallbacks(offCheck)
        if (!active || !displayOff) return
        val interactive = activity.getSystemService(PowerManager::class.java)?.isInteractive == true
        val covered = ProximityLock.covers(interactive, offSinceMs, SystemClock.elapsedRealtime())
        proximityEdge.off(covered, ScreenKeepPolicy.proximityNearText(ProximityLock.near))?.let(::log)
        if (covered) counters.proximityOff(SystemClock.elapsedRealtime())
        if (!covered) {
            exit(ScreenLock.Exit.SCREENOFF)
        } else if (ProximityLock.near == false) {
            main.postDelayed(offCheck, ScreenKeepPolicy.PROXIMITY_GRACE_MS + OFF_CHECK_SLACK_MS)
        }
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

        /** Added to the sensor's grace for the second look at an off display, so it is surely past it. */
        private const val OFF_CHECK_SLACK_MS = 100L
    }
}
