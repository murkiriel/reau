package com.reau.app.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.reau.app.settings.REauSettings
import com.reau.app.settings.ScreenKeepMode

/**
 * "Tela acesa com o celular bloqueado (teste)": while the projection runs and the rider's switch is on, the screen is lit again when it goes off
 * (a short screen wake lock that wakes it up), so the phone's own task killer (realme, Xiaomi) never finds REau in the background with the screen off.
 * The decisions are [ScreenKeepPolicy]'s; this holds the Android parts: the screen broadcasts, the timer, the wake lock and, if the
 * rider asked, the arming of the app's own activity over the lock screen ([ScreenKeepFront], [com.reau.app.ui.LockOverHost]): the service never starts an activity.
 * Every call and every callback is on the main thread, so nothing here needs a lock.
 *
 * It lights with a wake lock that always has a timeout and is never held past it, and it never asks for KEEP_SCREEN_ON. It lights in a pocket too: the rider
 * wanted the function to hold the phone awake wherever it is, so it reads no sensor.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
internal class ScreenKeeper(
    context: Context,
    private val settings: () -> REauSettings,
    private val log: (String) -> Unit = { com.reau.app.data.RideDiagnostics.log(ScreenKeepPolicy.TAG, it) },
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var receiver: BroadcastReceiver? = null
    private var lock: PowerManager.WakeLock? = null
    private var mode = ScreenKeepMode.ON_SCREEN_OFF
    private var lockUi = false
    private var offSinceMs = 0L
    private var lastWakeMs: Long? = null
    private var tick: Runnable? = null
    private var frontCheck: Runnable? = null

    /** Takes the switch as it is now (on the main thread): goes on, changes its mode, or goes off. Called when the projection runs and when a setting changes. */
    fun sync() {
        main.post {
            val s = settings()
            if (s.screenKeep) turnOn(s) else turnOff()
        }
    }

    /** Lets everything go (the projection stopped): the receiver, the timer, the sensor, the lock and the screen over the lock screen. */
    fun stop() {
        main.post { turnOff() }
    }

    private fun turnOn(s: REauSettings) {
        val changed = s.screenKeepMode != mode || s.screenKeepLockUi != lockUi
        mode = s.screenKeepMode
        lockUi = s.screenKeepLockUi
        // The app's activity shows over the lock screen from now on, as the maker's own app does (it is the activity in front when the rider locks the phone).
        ScreenKeepFront.arm(lockUi)
        if (receiver != null) {
            if (!changed) return
            log(ScreenKeepPolicy.onLine(mode, lockUi))
            if (!power().isInteractive) armTimer()
            return
        }
        val heard = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) screenOff() else screenOn()
            }
        }
        receiver = heard
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { ContextCompat.registerReceiver(app, heard, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { log("${com.reau.app.data.RideDiagnostics.WARN}listening to the screen failed: ${it.javaClass.simpleName}") }
        log(ScreenKeepPolicy.onLine(mode, lockUi))
        if (!power().isInteractive) {
            offSinceMs = now()
            armTimer()
        }
    }

    private fun turnOff() {
        // The projection ended (or the switch went off): the rider's pause for this ride ends with it.
        ScreenKeepFront.clearPause()
        ScreenKeepFront.arm(false)
        val heard = receiver ?: return
        receiver = null
        runCatching { app.unregisterReceiver(heard) }
        cancelTimer()
        lock?.takeIf { it.isHeld }?.release()
        frontCheck?.let { main.removeCallbacks(it) }
        frontCheck = null
        log(ScreenKeepPolicy.OFF_LINE)
    }

    private fun screenOff() {
        offSinceMs = now()
        cancelTimer()
        val s = settings()
        if (!s.screenKeep) return turnOff()
        mode = s.screenKeepMode
        lockUi = s.screenKeepLockUi
        ScreenKeepFront.arm(lockUi)
        if (mode == ScreenKeepMode.ON_SCREEN_OFF) ask(ScreenKeepPolicy.Trigger.SCREEN_OFF) else armTimer()
    }

    private fun screenOn() {
        // The screen is on: nothing to light. The lock is let go at once (the maker's own app does the same),
        // so the screen follows the phone's own timeout instead of being held for the lock's whole timeout.
        lock?.takeIf { it.isHeld }?.release()
        cancelTimer()
    }

    private fun armTimer() {
        cancelTimer()
        val delay = ScreenKeepPolicy.nextDelayMs(mode, offSinceMs, now()) ?: return
        val next = Runnable { fire() }
        tick = next
        main.postDelayed(next, delay)
    }

    private fun cancelTimer() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
    }

    /** The interval of a timer mode is up: the screen is lit if it is still off, and the next try is set. */
    private fun fire() {
        tick = null
        val s = settings()
        if (!s.screenKeep) return turnOff()
        // A screen that is on again re-arms the timer when it goes off.
        if (power().isInteractive) return
        ask(ScreenKeepPolicy.Trigger.TIMER)
        armTimer()
    }

    /** The decision for [trigger], and the light of the screen at once when it is to be lit. */
    private fun ask(trigger: ScreenKeepPolicy.Trigger) {
        if (decide(trigger) == ScreenKeepPolicy.Decision.WAKE) wake(trigger)
    }

    private fun decide(trigger: ScreenKeepPolicy.Trigger) = ScreenKeepPolicy.decide(
        enabled = receiver != null, mode = mode, trigger = trigger, screenOn = power().isInteractive,
        sinceLastWakeMs = lastWakeMs?.let { now() - it }, pausedByRider = ScreenKeepFront.paused,
    )

    private fun wake(trigger: ScreenKeepPolicy.Trigger) {
        val held = lock ?: power().newWakeLock(WAKE_FLAGS, LOCK_TAG).also {
            it.setReferenceCounted(false)
            lock = it
        }
        // Always with a timeout, so it can never be left held: the screen lights and goes back to the phone's own timeout.
        held.acquire(if (trigger == ScreenKeepPolicy.Trigger.SCREEN_OFF) SCREEN_OFF_LOCK_MS else TIMER_LOCK_MS)
        val at = now()
        lastWakeMs = at
        log(ScreenKeepPolicy.wakeLine(trigger, ScreenKeepPolicy.secondsOff(offSinceMs, at)))
        if (lockUi) checkFront()
    }

    /**
     * A moment after lighting the screen with the lock screen choice on: was the app's activity the one in front? The service starts nothing; the activity is already
     * there (or not: the rider may be in another app) and says `lockui shown` itself when it resumes over the lock screen. The log here says the cases where it did not.
     */
    private fun checkFront() {
        frontCheck?.let { main.removeCallbacks(it) }
        val check = Runnable {
            frontCheck = null
            val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            ScreenKeepPolicy.frontLine(ScreenKeepFront.resumed, locked)?.let(log)
        }
        frontCheck = check
        main.postDelayed(check, FRONT_CHECK_MS)
    }

    private fun power() = app.getSystemService(PowerManager::class.java)

    companion object {
        /** The tag of the wake lock, as `dumpsys power` shows it. */
        const val LOCK_TAG = "reau:screen-keep"

        /**
         * A dim screen wake lock that wakes the screen up: the dimmest that lights it, since a bright one costs more battery. SCREEN_DIM_WAKE_LOCK is
         * deprecated since Android 4.2 (the window's FLAG_KEEP_SCREEN_ON is the advised way), and it is the only way to light a screen from a service
         * with no window; the activity over the lock screen does not use KEEP_SCREEN_ON either.
         */
        @Suppress("DEPRECATION") // SCREEN_DIM_WAKE_LOCK is the only way to light the screen from a service with no window (see above)
        internal const val WAKE_FLAGS = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP

        /** How long the lock of a screen-off lights, and of a timer's light: the lock is let go by Android itself when it runs out. */
        internal const val SCREEN_OFF_LOCK_MS = 10_000L
        internal const val TIMER_LOCK_MS = 5_000L

        /** How long after a light of the screen the log says whether the app's activity was in front. */
        internal const val FRONT_CHECK_MS = 1_500L
    }
}
