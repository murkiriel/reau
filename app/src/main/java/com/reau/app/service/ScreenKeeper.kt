package com.reau.app.service

import android.app.KeyguardManager
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
import androidx.core.content.ContextCompat
import com.reau.app.settings.REauSettings

/**
 * "Modo de bloqueio do REau (teste)": while the projection runs and the rider's switch is on, the screen is lit again when it goes off
 * (a bright screen wake lock that wakes it up, for 10 s), so the phone's own task killer (realme, Xiaomi) never finds REau in the background with the screen off.
 * The decisions are [ScreenKeepPolicy]'s; this holds the Android parts: the default display's listener (the screen going off or on is read from its state:
 * during a projection of Android Auto the phone stays "interactive" and no SCREEN_OFF broadcast comes), the broadcast as a second source, a safety tick of
 * [ScreenKeepPolicy.TICK_MS], the wake lock and, if the
 * rider asked, the arming of the app's own activity over the lock screen ([ScreenKeepFront], [com.reau.app.ui.LockOverHost]): the service never starts an activity.
 * Every call and every callback is on the main thread, so nothing here needs a lock.
 *
 * It lights with a wake lock that always has a timeout and is never held past it, and it never asks for KEEP_SCREEN_ON. It lights in a pocket too: the rider
 * wanted the function to hold the phone awake wherever it is, so it reads no sensor of its own. The one exception (0.3.99): while REau's lock mode holds the proximity
 * wake lock ([ProximityLock]) the display goes off on purpose with the sensor covered, the phone still awake, and lighting it would only fight the sensor and spend the
 * battery. A display off while the lock is held and the phone interactive is then not lit ([ScreenKeepPolicy.proximityCovers], with the sensor's reading to tell a real off
 * during a projection of Android Auto, where `isInteractive` stays true); it counts no gap and no dark retry, and a real off is lit as before.
 *
 * Since 0.3.99 it is also the watch on the phone's real lock: its receiver (SCREEN_OFF, SCREEN_ON, USER_PRESENT), its display listener and its 5 s tick tell [ScreenWatch], which
 * writes the display's changes, the lock screen coming up or going away (with the likely cause) and a snapshot every minute ([LockLog]), whether REau's lock mode is on or not.
 * A failure of the wake lock or of a listener is a warning in the log.
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
    private var offSinceMs = 0L
    private var lastWakeMs: Long? = null
    private var safety: Runnable? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private val edge = ScreenKeepPolicy.DisplayEdge()

    /** The last try to light the screen was held back by the gap, and the screen is still off: the safety tick tries again. */
    private var retry = false

    /** A light was taken and the screen was not seen on since: if it is still off a while after, the safety tick lights it again ([ScreenKeepPolicy.darkRetry]). */
    private var lightAsked = false
    private var darkTries = 0
    private var frontCheck: Runnable? = null

    /** The last decision was "not lit, the proximity lock turned it off" (said once in the log, looked at again by the tick and after the sensor's grace). */
    private var proximityNoted = false
    private var proximityCheck: Runnable? = null

    /** Takes the switch as it is now (on the main thread): goes on or goes off. Called when the projection runs and when a setting changes. */
    fun sync() {
        main.post {
            if (settings().lockMode) turnOn() else turnOff()
        }
    }

    /** Lets everything go (the projection stopped): the receiver, the display listener, the safety tick, the lock and the screen over the lock screen. */
    fun stop() {
        main.post { turnOff() }
    }

    private fun turnOn() {
        // The app's activity shows over the lock screen from now on, as the maker's own app does (it is the activity in front when the rider locks the phone).
        ScreenKeepFront.arm(true)
        if (receiver != null) return
        val heard = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The unlock (USER_PRESENT) only tells the state lines that the phone was unlocked: it is neither the screen going off nor coming on.
                ScreenWatch.screenEvent(intent.action)
                if (intent.action != Intent.ACTION_USER_PRESENT) seen(intent.action == Intent.ACTION_SCREEN_OFF, ScreenKeepPolicy.Via.BROADCAST)
            }
        }
        receiver = heard
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        runCatching { ContextCompat.registerReceiver(app, heard, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { log(LockLog.warnLine("listening to the screen", it)) }
        log(ScreenKeepPolicy.ON_LINE)
        // The state lines (the lock screen, the display, the minute's snapshot) are on for as long as the keeper is, with REau's own lock mode or without it.
        ScreenWatch.begin(app, ScreenWatch.SOURCE_KEEPER)
        edge.reset(displayOff())
        watchTheDisplay()
        // The screen is already off as the function begins (the phone in a pocket as the projection starts): no event will say so, so it is lit now.
        if (edge.off) screenOff(ScreenKeepPolicy.REASON_START)
        armSafety()
    }

    /** The default display's state is the source of "the screen went off or on": each change is seen here, as it happens. */
    private fun watchTheDisplay() {
        val manager = app.getSystemService(DisplayManager::class.java)
        if (manager == null) {
            log(LockLog.unavailableLine("display manager"))
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    ScreenWatch.displayChanged()
                    seen(displayOff(), ScreenKeepPolicy.Via.LISTENER)
                }
            }
        }
        displayListener = listener
        runCatching { manager.registerDisplayListener(listener, main) }.onFailure { log(LockLog.warnLine("display listener", it)) }
    }

    /** Whether the default display is not on now (off, doze, suspended); an unreadable display counts as on. */
    private fun displayOff(): Boolean =
        app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let(ScreenKeepPolicy::displayOff) == true

    /** The screen was seen off or on by [via]: said once, from whoever sees it first, and then acted on. */
    private fun seen(off: Boolean, via: ScreenKeepPolicy.Via) {
        if (receiver == null) return
        val line = edge.heard(off, via) ?: return
        log(line)
        if (off) screenOff() else screenOn()
    }

    /**
     * The safety net: every [ScreenKeepPolicy.TICK_MS] the display is read, in case an event did not come, a try held back by the gap is made again, and so is a light that
     * did not light the screen (it is still off [ScreenKeepPolicy.DARK_AFTER_MS] after it: the maker may ignore the wake-up, and no event comes when nothing changed).
     */
    private fun armSafety() {
        safety?.let { main.removeCallbacks(it) }
        val next = Runnable {
            safety = null
            if (receiver == null) return@Runnable
            // The lock screen is looked at at each tick too, in case no event told of it (the phone locks some seconds after the screen goes off).
            ScreenWatch.look()
            seen(displayOff(), ScreenKeepPolicy.Via.TICK)
            if (edge.off) {
                // The proximity lock's own off goes through ask() too (it decides "not lit" and says it once) and never reaches the dark retry, so none is counted.
                if (retry || proximityNoted || proximityNow()) {
                    ask()
                } else if (ScreenKeepPolicy.darkRetry(true, lightAsked, lastWakeMs?.let { now() - it }, darkTries)) {
                    darkTries++
                    ask(ScreenKeepPolicy.REASON_DARK)
                }
            }
            armSafety()
        }
        safety = next
        main.postDelayed(next, ScreenKeepPolicy.TICK_MS)
    }

    private fun turnOff() {
        // The projection ended (or the switch went off): the rider's pause for this ride ends with it.
        ScreenKeepFront.clearPause()
        ScreenKeepFront.arm(false)
        val heard = receiver ?: return
        receiver = null
        runCatching { app.unregisterReceiver(heard) }.onFailure { log(LockLog.warnLine("stop listening to the screen", it)) }
        displayListener?.let { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
        safety?.let { main.removeCallbacks(it) }
        safety = null
        retry = false
        lightAsked = false
        darkTries = 0
        proximityNoted = false
        proximityCheck?.let { main.removeCallbacks(it) }
        proximityCheck = null
        runCatching { lock?.takeIf { it.isHeld }?.release() }.onFailure { log(LockLog.warnLine("wake lock release", it)) }
        frontCheck?.let { main.removeCallbacks(it) }
        frontCheck = null
        log(ScreenKeepPolicy.OFF_LINE)
        ScreenWatch.end(ScreenWatch.SOURCE_KEEPER)
    }

    private fun screenOff(reason: String = ScreenKeepPolicy.REASON_SCREENOFF) {
        offSinceMs = now()
        if (!settings().lockMode) return turnOff()
        ScreenKeepFront.arm(true)
        ask(reason)
    }

    private fun screenOn() {
        // The screen is on: nothing to light. The lock is let go at once (the maker's own app does the same), and ON_AFTER_RELEASE keeps the screen
        // on for the phone's own timeout from then, instead of the lock's whole timeout.
        runCatching { lock?.takeIf { it.isHeld }?.release() }.onFailure { log(LockLog.warnLine("wake lock release", it)) }
        retry = false
        lightAsked = false
        darkTries = 0
        proximityNoted = false
    }

    /** Whether the display that is off now is off because REau's proximity lock holds it so ([ScreenKeepPolicy.proximityCovers], read from [ProximityLock] and from the phone's interactive state). */
    private fun proximityNow(): Boolean =
        ProximityLock.held.value && ProximityLock.covers(power().isInteractive, offSinceMs, now())

    /** The decision, and the light of the screen at once when it is to be lit; [reason] is what asked for it, for the log's `wake` line. */
    private fun ask(reason: String = ScreenKeepPolicy.REASON_SCREENOFF) {
        val why = ScreenKeepPolicy.why(receiver != null, lastWakeMs?.let { now() - it }, ScreenKeepFront.paused, proximityNow())
        // Held back by the gap, the screen still off: the safety tick asks again (a screen-off right after a light would otherwise stay dark).
        retry = why == ScreenKeepPolicy.WHY_GAP
        if (why == ScreenKeepPolicy.WHY_PROXIMITY) return holdBackForProximity()
        proximityNoted = false
        if (why == null) wake(reason) else log(ScreenKeepPolicy.nothingLine(why))
    }

    /**
     * The display is off by the proximity lock: said once per change, and, while the sensor reads uncovered (the sensor's event may be late, or the off may be real), looked at
     * again once the grace of [ScreenKeepPolicy.PROXIMITY_GRACE_MS] has passed. A sensor that stays covered is looked at by the tick, which stays silent.
     */
    private fun holdBackForProximity() {
        if (!proximityNoted) {
            proximityNoted = true
            log(ScreenKeepPolicy.nothingLine(ScreenKeepPolicy.WHY_PROXIMITY))
        }
        if (ProximityLock.near != false || proximityCheck != null) return
        val check = Runnable {
            proximityCheck = null
            if (receiver != null && edge.off) ask()
        }
        proximityCheck = check
        main.postDelayed(check, ScreenKeepPolicy.PROXIMITY_GRACE_MS + CHECK_SLACK_MS)
    }

    private fun wake(reason: String) {
        val held = runCatching {
            lock ?: power().newWakeLock(WAKE_FLAGS, LOCK_TAG).also {
                it.setReferenceCounted(false)
                lock = it
            }
        }.onFailure { log(LockLog.warnLine("wake lock create", it)) }.getOrNull() ?: return
        // Always with a timeout, so it can never be left held: the screen lights and goes back to the phone's own timeout.
        if (runCatching { held.acquire(LOCK_MS) }.onFailure { log(LockLog.warnLine("wake lock acquire", it)) }.isFailure) return
        ScreenWatch.wake()
        val at = now()
        lastWakeMs = at
        lightAsked = true
        log(ScreenKeepPolicy.wakeLine(ScreenKeepPolicy.secondsOff(offSinceMs, at), reason))
        checkFront()
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
         * A bright screen wake lock that wakes the screen up and keeps it on for the phone's own timeout after the lock is let go (ON_AFTER_RELEASE), as the maker's own
         * app does: the dim one lit the screen too dark to see it was on. FULL_WAKE_LOCK is deprecated since Android 4.2 (the window's FLAG_KEEP_SCREEN_ON is the advised way),
         * and it is the only way to light a screen from a service with no window.
         */
        @Suppress("DEPRECATION") // FULL_WAKE_LOCK is the only way to light the screen from a service with no window (see above)
        internal const val WAKE_FLAGS = PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE

        /** How long the lock lights: it is let go by Android itself when it runs out (and by [screenOn] when the screen is on). */
        internal const val LOCK_MS = 10_000L

        /** How long after a light of the screen the log says whether the app's activity was in front. */
        internal const val FRONT_CHECK_MS = 1_500L

        /** Added to the proximity grace for the second look, so it is surely past it. */
        private const val CHECK_SLACK_MS = 100L
    }
}
