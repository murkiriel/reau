package com.reau.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.LockLog
import com.reau.app.service.ProximityLock
import com.reau.app.service.ScreenLock
import com.reau.app.service.ScreenLockLog

/**
 * The Android side of REau's proximity lock (0.3.99 test build): inside the lock mode, `PROXIMITY_SCREEN_OFF_WAKE_LOCK` (the one the dialer holds at the ear) turns the display
 * off while the sensor is covered (a pocket) and on again when it is uncovered, with the phone never locking, which is the whole point: the display's power is the biggest part of
 * what the mode spends. [ScreenLockMode] decides when to [acquire] and [release]; this holds the one lock and the sensor reading that confirms a covered display ([ProximityLock]).
 *
 * The lock is one instance, not reference counted, taken only if the phone supports its level, and taken with no timeout: a timeout that ran out would leave [ProximityLock.held]
 * saying "held" for a lock that is not (and [com.reau.app.service.ScreenKeeper] would then wrongly stay quiet), and every way out calls [release], which is the one that clears both
 * (the process dying frees the lock by itself). Never throws. Main thread. Every change of the sensor is written (`sensor proximity=near value=0.0`, the first at once and then at most one in
 * [ScreenLockLog.GROUP_MS] with the count, and the range once at the registration), and every failure (no power manager, no sensor, a listener that would not register, a release that
 * threw) is a warning. With no sensor to hear (the manager, the sensor or the listener missing) the lock is let go at once ([ScreenLock.PROXIMITY_NO_SENSOR]): a covered display could
 * not be told from a real off, and the mode goes on as before the lock.
 */
internal class ProximityHold(
    private val context: Context,
    private val main: Handler,
    private val log: (String) -> Unit = { RideDiagnostics.log(ScreenLock.TAG, it) },
) {
    private var lock: PowerManager.WakeLock? = null
    private var unsupportedSaid = false
    private var listener: SensorEventListener? = null

    /** Told when the sensor changes while the lock is held (the mode looks at the display again). */
    var onSensorChange: (() -> Unit)? = null

    /** Whether the lock is held now. */
    val held: Boolean get() = ProximityLock.held.value

    /** Takes the lock for [reason] (see [ScreenLock.PROXIMITY_ENTER]); nothing happens if it is held already or the phone does not support it (said once). */
    @SuppressLint("WakelockTimeout") // no timeout on purpose, see the class doc: every exit calls release()
    fun acquire(reason: String) {
        if (held) return
        val power = context.getSystemService(PowerManager::class.java)
        if (power == null) {
            log(LockLog.unavailableLine("power manager"))
            return
        }
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (!unsupportedSaid) log(ScreenLock.PROXIMITY_UNSUPPORTED_LINE)
            unsupportedSaid = true
            return
        }
        runCatching {
            val taken = lock ?: power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOCK_TAG).also {
                it.setReferenceCounted(false)
                lock = it
            }
            if (!taken.isHeld) taken.acquire()
            ProximityLock.hold(SystemClock.elapsedRealtime())
            val listening = listen()
            log(ScreenLock.proximityLine(true, reason))
            // No sensor to hear: a covered display could never be told from a real off (the power button during a projection leaves the phone interactive), so the lock goes at once
            // and the mode goes on as it did before the lock.
            if (!listening) release(ScreenLock.PROXIMITY_NO_SENSOR)
        }.onFailure {
            release("failed")
            log(LockLog.warnLine("proximity lock", it))
        }
    }

    /**
     * Lets the lock go for [reason], whichever way: the sensor listener goes, the lock is released if it is held (`isHeld` first, so a lock that is not held is never released) and
     * [ProximityLock] says "not held", even if the release throws. [waitForFar] releases with `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`: the display stays off until the sensor is
     * uncovered. Only the end of the projection asks it (the phone may be in a pocket then, and nothing would be there to look at the screen); every other way lets go at once
     * (`release(0)`), the rider having the phone in the hand or the keeper wanting the screen lit.
     */
    fun release(reason: String, waitForFar: Boolean = false) {
        var wasHeld = false
        runCatching {
            stopListening()
            val taken = lock
            if (taken != null && taken.isHeld) {
                wasHeld = true
                taken.release(if (waitForFar) PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY else 0)
            }
        }.onFailure { log(LockLog.warnLine("proximity lock release", it)) }
        // After the release, a thrown one included: the shared state never says "held" for a lock that was let go.
        ProximityLock.letGo()
        if (wasHeld) log(ScreenLock.proximityLine(false, reason))
    }

    /** Registers the sensor's listener; false when it cannot be heard (no manager, no sensor, a listener that would not register), and the caller lets the lock go. */
    private fun listen(): Boolean {
        stopListening()
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        return when {
            sensors == null -> false.also { log(LockLog.unavailableLine("sensor manager")) }
            sensor == null -> false.also { log(LockLog.unavailableLine("proximity sensor")) }
            else -> register(sensors, sensor)
        }
    }

    private fun register(sensors: SensorManager, sensor: Sensor): Boolean {
        // One line of the sensor in each GROUP_MS, with the count: a sensor that flaps in a pocket would fill the log.
        val lines = ScreenLockLog.Grouper()
        val reader = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull() ?: return
                val near = ProximityLock.isNear(value, sensor.maximumRange)
                if (ProximityLock.sensor(near, SystemClock.elapsedRealtime())) {
                    // The raw value of the change (the range is said once, at the registration); the first at once, then at most one in [ScreenLockLog.GROUP_MS] with the count.
                    lines.hit(SystemClock.elapsedRealtime())?.let { log(ScreenLockLog.sensorLine(near, value, it)) }
                    onSensorChange?.invoke()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // Registered only while the lock is held, when the system keeps the sensor on for the lock anyway.
        if (!sensors.registerListener(reader, sensor, SensorManager.SENSOR_DELAY_NORMAL, main)) {
            log(LockLog.unavailableLine("proximity sensor listener"))
            return false
        }
        listener = reader
        log(ScreenLockLog.sensorRegisteredLine(sensor.maximumRange))
        return true
    }

    private fun stopListening() {
        val reader = listener ?: return
        listener = null
        runCatching { context.getSystemService(SensorManager::class.java)?.unregisterListener(reader) }
            .onFailure { log(LockLog.warnLine("proximity sensor listener release", it)) }
    }

    private companion object {
        /** The tag of the wake lock, as `dumpsys power` shows it. */
        const val LOCK_TAG = "reau:proximity"
    }
}
