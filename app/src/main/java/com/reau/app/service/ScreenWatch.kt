package com.reau.app.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.reau.app.data.RideDiagnostics

/**
 * The Android side of the state lines of [LockLog] (0.3.99 test build): reads the display, the power state, the lock screen, the proximity sensor's last reading and the battery,
 * and hands them to the one shared [ScreenTrace]. It listens to nothing of its own: [ScreenKeeper] (its receiver of SCREEN_OFF, SCREEN_ON and USER_PRESENT, its display listener
 * and its 5 s tick), [com.reau.app.ui.ScreenLockMode] (its display listener and SCREEN_OFF receiver) and [com.reau.app.ui.LockOverHost] (its unlock receiver and the result of the
 * dismiss) call in when they hear something, so nothing here runs per video frame and nothing is registered twice. It is begun by each of the two ([SOURCE_KEEPER], [SOURCE_LOCK])
 * and runs while either is on: the lock screen is watched whenever the keeper runs, with REau's own lock mode or without, and a snapshot is written at once and every [LockLog.SNAPSHOT_MS].
 * Nothing needs a new permission: the sticky battery broadcast, the keyguard and the power manager are free to read. A failed read is a warning (said once per watch, not at every look).
 * Main thread only.
 */
@Suppress("TooManyFunctions") // one small function per thing that is heard or read; splitting them would only scatter the same thing
@SuppressLint("StaticFieldLeak") // [app] is the application context, never an activity, and is let go with the last source
internal object ScreenWatch {
    /** The source that is [ScreenKeeper] (the projection runs with the switch on). */
    const val SOURCE_KEEPER = "keeper"

    /** The source that is REau's own lock mode. */
    const val SOURCE_LOCK = "lock"

    private val trace = ScreenTrace { RideDiagnostics.log(LockLog.TAG, it) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // The application context only (never an activity's), and let go with the last source.
    private var app: Context? = null
    private var ticker: Runnable? = null
    private val said = hashSetOf<String>()

    /** How the lock mode's window is read (its brightness and the keep-on flag), set by the lock mode while it is on; null when there is no such window. */
    var window: (() -> LockLog.WindowReading?)? = null

    /** How many times the keeper lit the screen since the first source began. */
    val wakes: Long get() = trace.wakes

    /** How many times the lock screen came up since the first source began. */
    val keyguardLocks: Long get() = trace.keyguardLocks

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** [source] is on from now: the first one starts the clock and the minute's snapshot; each one writes a snapshot at once. */
    fun begin(context: Context, source: String) {
        app = context.applicationContext
        if (trace.begin(source, now())) said.clear()
        snapshot()
        if (ticker == null) schedule()
    }

    /** [source] is off; the last one stops the snapshot. */
    fun end(source: String) {
        if (source == SOURCE_LOCK) window = null
        if (!trace.end(source)) return
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
        app = null
    }

    /** The phone's own SCREEN_OFF, SCREEN_ON or USER_PRESENT was heard: remembered for the cause, and the lock screen is looked at. */
    fun screenEvent(action: String?) {
        if (!trace.active) return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> trace.screenOff(now())
            Intent.ACTION_USER_PRESENT -> trace.userPresent(now())
        }
        look()
    }

    /** The default display changed (the keeper's and the lock mode's listeners): its state and the lock screen are looked at. */
    fun displayChanged() = look()

    /** Android said the dismiss of the lock screen the rider asked for succeeded. */
    fun dismissed() {
        if (!trace.active) return
        trace.dismissed(now())
        look()
    }

    /** The keeper lit the screen (the lock mode's summary counts them). */
    fun wake() = trace.wake()

    /** A look at the display and the lock screen, to say a change (the keeper's 5 s tick, and every event above). */
    fun look() {
        if (trace.active) trace.observe(read(), now())
    }

    private fun schedule() {
        val next = Runnable {
            ticker = null
            if (!trace.active) return@Runnable
            snapshot()
            schedule()
        }
        ticker = next
        main.postDelayed(next, LockLog.SNAPSHOT_MS)
    }

    /** The minute's line: what the phone is, what REau's window is, the proximity lock, the projection and the battery. */
    private fun snapshot() {
        val context = app ?: return
        val at = now()
        val reading = read()
        trace.observe(reading, at)
        val projection = ProjectionService.projection.value
        val window = guarded("lock window") { window?.invoke() }
        val line = LockLog.snapshotLine(
            LockLog.Snapshot(
                atSeconds = trace.seconds(at), reading = reading, window = window, proximityHeld = ProximityLock.held.value,
                phase = projection.phase.name.lowercase(), dash = projection.dash.name.lowercase(), battery = guarded("battery") { battery(context) },
            ),
        )
        RideDiagnostics.log(LockLog.TAG, line)
    }

    /** The display, the power state, the lock screen and the sensor now; a manager that is missing or throws is said once and its part is null. */
    private fun read(): LockLog.Reading {
        val context = app
        val keyguard = context?.let { guarded("keyguard manager") { it.getSystemService(KeyguardManager::class.java) } }
        if (context != null && keyguard == null) warnOnce(LockLog.unavailableLine("keyguard manager"))
        return LockLog.Reading(
            displayState = context?.let { guarded("display state") { displayState(it) } },
            interactive = context?.let { guarded("power state") { it.getSystemService(PowerManager::class.java)?.isInteractive } },
            keyguardLocked = guarded("keyguard state") { keyguard?.isKeyguardLocked },
            secure = guarded("keyguard secure") { keyguard?.isDeviceSecure },
            near = ProximityLock.near,
            lockOn = trace.has(SOURCE_LOCK),
        )
    }

    private fun displayState(context: Context): Int? = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state

    /** The battery from the sticky broadcast (no receiver is registered, no permission is asked). */
    private fun battery(context: Context): LockLog.BatteryReading? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return LockLog.BatteryReading.from(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1), tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        )
    }

    private fun <T> guarded(what: String, block: () -> T?): T? =
        runCatching(block).onFailure { warnOnce(LockLog.warnLine(what, it)) }.getOrNull()

    private fun warnOnce(line: String) {
        if (said.add(line)) RideDiagnostics.log(LockLog.TAG, line)
    }
}
