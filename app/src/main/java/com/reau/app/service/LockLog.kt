package com.reau.app.service

import com.reau.app.data.RideDiagnostics
import java.util.Locale

/**
 * The ride log's lines about the phone's real state while REau holds the screen (0.3.99 test build), with no Android in it: is the phone LOCKED (the lock screen, the PIN), what the
 * display and the sensor are doing, and a snapshot of everything once a minute. They answer one question for the pilots: did the phone lock on its own in the pocket? Every line says
 * states, numbers and times only, no personal data. They are under the tag [TAG] (`[screen-watch]`), written by [ScreenTrace] (which decides what is a change) and fed by
 * [ScreenWatch] (which reads Android), from whoever listens already: [ScreenKeeper]'s receiver, display listener and 5 s tick, the lock mode's own listener and receiver, and the
 * unlock receiver of [com.reau.app.ui.LockOverHost]. The lines of the lock mode itself (brightness, the slide, Back, focus, the summary) are in [ScreenLockLog], under `[lock]`.
 */
@Suppress("TooManyFunctions") // one small function per line of the log; splitting them would only scatter the same thing
internal object LockLog {
    /** The tag of the state lines: `display`, `keyguard` and `snapshot`. */
    const val TAG = "screen-watch"

    /** How often the snapshot is written while the lock mode or the keeper is on. */
    const val SNAPSHOT_MS = 60_000L

    /** A screen-off this recent (ms) is the likely cause of a lock screen coming up (the phone locks at the off, or a few seconds later by its own delay). */
    const val CAUSE_WINDOW_MS = 120_000L

    /** An unlock event this recent (ms) is the likely cause of the lock screen going away. */
    const val UNLOCK_WINDOW_MS = 5_000L

    const val CAUSE_POWER_OR_TIMEOUT = "power-key-or-timeout"
    const val CAUSE_SENSOR_NEAR = "screen-off-sensor-near"
    const val CAUSE_USER_PRESENT = "user-present"
    const val CAUSE_DISMISSED = "dismissed"
    const val CAUSE_UNKNOWN = "unknown"

    /**
     * What the phone says at one instant; a part Android would not say is null. [displayState] is `Display.state` of the default display, [interactive] `PowerManager.isInteractive`,
     * [keyguardLocked] `KeyguardManager.isKeyguardLocked`, [secure] `isDeviceSecure` (a PIN, pattern or password is set), [near] the proximity sensor (null: not known, it is read only
     * while the lock mode holds the proximity lock) and [lockOn] whether REau's lock mode is on.
     */
    data class Reading(
        val displayState: Int?,
        val interactive: Boolean?,
        val keyguardLocked: Boolean?,
        val secure: Boolean?,
        val near: Boolean?,
        val lockOn: Boolean,
    )

    /** The lock screen's window: its brightness (`screenBrightness`, below zero is the phone's own) and whether FLAG_KEEP_SCREEN_ON is set. */
    data class WindowReading(val brightness: Float, val keepOn: Boolean)

    /** The battery as the sticky ACTION_BATTERY_CHANGED says it: the percent, the charger, the temperature in degrees. */
    data class BatteryReading(val percent: Int?, val charging: Boolean?, val tempC: Float?) {
        companion object {
            private const val PERCENT = 100
            private const val TENTHS = 10f

            /** From the intent's extras: [level] of [scale], [plugged] (0 is no charger) and [tempTenths] (tenths of a degree; [Int.MIN_VALUE] or less when the intent had none). */
            fun from(level: Int, scale: Int, plugged: Int, tempTenths: Int): BatteryReading = BatteryReading(
                percent = if (level >= 0 && scale > 0) level * PERCENT / scale else null,
                charging = if (plugged >= 0) plugged != 0 else null,
                tempC = if (tempTenths > Int.MIN_VALUE) tempTenths / TENTHS else null,
            )
        }
    }

    /** What the once-a-minute line says: [atSeconds] since the watch began, the phone's [reading], the lock window, the proximity lock, the projection and the battery. */
    data class Snapshot(
        val atSeconds: Long,
        val reading: Reading,
        val window: WindowReading?,
        val proximityHeld: Boolean,
        val phase: String,
        val dash: String,
        val battery: BatteryReading?,
    )

    private fun flag(value: Boolean?): String = value?.toString() ?: "?"

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    /** The display's state by its name, `?` when not known. */
    private fun displayWord(state: Int?): String = state?.let(ScreenLock::displayName) ?: "?"

    /** One change of the default display's state, with `isInteractive` of the same instant: `display state=OFF interactive=false`. */
    fun displayLine(state: Int, interactive: Boolean?) = "display state=${ScreenLock.displayName(state)} interactive=${flag(interactive)}"

    /**
     * The lock screen coming up or going away: `keyguard locked=true at=312s secure=true interactive=false display=OFF sensor=far lock=on cause=power-key-or-timeout`.
     * [atSeconds] is since the watch began, [reading] what the phone says now and [cause] what [keyguardCause] deduced.
     */
    fun keyguardLine(locked: Boolean, atSeconds: Long, reading: Reading, cause: String): String =
        "keyguard locked=$locked at=${atSeconds}s secure=${flag(reading.secure)} interactive=${flag(reading.interactive)} " +
            "display=${displayWord(reading.displayState)} sensor=${ScreenKeepPolicy.proximityNearText(reading.near)} lock=${onOff(reading.lockOn)} cause=$cause"

    /**
     * The likely cause of the lock screen changing, from what was heard before it: [sinceScreenOffMs] since the screen went off (the broadcast or the display), [sinceUserPresentMs]
     * since ACTION_USER_PRESENT, [sinceDismissMs] since Android said a dismiss of the lock screen succeeded (null: not heard).
     * Locked: with the screen off (or heard off in the last [CAUSE_WINDOW_MS]) and the sensor not covered, the power button or the phone's own timeout ([CAUSE_POWER_OR_TIMEOUT]); with the sensor
     * covered [CAUSE_SENSOR_NEAR]; otherwise [CAUSE_UNKNOWN]. Unlocked: [CAUSE_USER_PRESENT] or [CAUSE_DISMISSED] when heard in the last [UNLOCK_WINDOW_MS], else [CAUSE_UNKNOWN].
     */
    fun keyguardCause(locked: Boolean, reading: Reading, sinceScreenOffMs: Long?, sinceUserPresentMs: Long?, sinceDismissMs: Long?): String = when {
        !locked -> when {
            sinceUserPresentMs != null && sinceUserPresentMs in 0..UNLOCK_WINDOW_MS -> CAUSE_USER_PRESENT
            sinceDismissMs != null && sinceDismissMs in 0..UNLOCK_WINDOW_MS -> CAUSE_DISMISSED
            else -> CAUSE_UNKNOWN
        }
        !screenWasOff(reading, sinceScreenOffMs) -> CAUSE_UNKNOWN
        reading.near == true -> CAUSE_SENSOR_NEAR
        else -> CAUSE_POWER_OR_TIMEOUT
    }

    private fun screenWasOff(reading: Reading, sinceScreenOffMs: Long?): Boolean =
        reading.displayState?.let(ScreenKeepPolicy::displayOff) == true || reading.interactive == false ||
            (sinceScreenOffMs != null && sinceScreenOffMs in 0..CAUSE_WINDOW_MS)

    /**
     * The retrato of every minute: `snapshot at=312s display=ON interactive=true keyguard=false secure=true lock=on brightness=0.01 keep-on=true prox-held=true sensor=far
     * phase=running dash=ready battery=87% charging=false temp=31.5C`. A part not known is `-` (or `?` for a yes or no), the window's own brightness is `default`.
     */
    fun snapshotLine(s: Snapshot): String {
        val r = s.reading
        val brightness = s.window?.let { if (it.brightness < 0f) "default" else it.brightness.toString() } ?: "-"
        val battery = s.battery
        val percent = battery?.percent?.let { "$it%" } ?: "-"
        val temp = battery?.tempC?.let { String.format(Locale.US, "%.1fC", it) } ?: "-"
        return "snapshot at=${s.atSeconds}s display=${displayWord(r.displayState)} interactive=${flag(r.interactive)} " +
            "keyguard=${flag(r.keyguardLocked)} secure=${flag(r.secure)} lock=${onOff(r.lockOn)} brightness=$brightness " +
            "keep-on=${s.window?.keepOn?.toString() ?: "-"} prox-held=${s.proximityHeld} sensor=${ScreenKeepPolicy.proximityNearText(r.near)} " +
            "phase=${s.phase} dash=${s.dash} battery=$percent charging=${flag(battery?.charging)} temp=$temp"
    }

    /** A failure of something these lines depend on, with the exception's name only (never its message): `W: wake lock acquire failed: SecurityException`. */
    fun warnLine(what: String, error: Throwable): String = "${RideDiagnostics.WARN}$what failed: ${error.javaClass.simpleName}"

    /** The same for something Android would not hand over (a service that is null): `W: keyguard manager unavailable`. */
    fun unavailableLine(what: String): String = "${RideDiagnostics.WARN}$what unavailable"

    /** The words of the outcomes of Android's dismiss of the lock screen, for [dismissLine]. */
    const val DISMISS_ASKED = "asked"
    const val DISMISS_SUCCEEDED = "succeeded"
    const val DISMISS_CANCELLED = "cancelled"
    const val DISMISS_ERROR = "error"

    /** The screen over the lock screen asked Android to dismiss it, and what came back (under the screen-keep tag, as the other `lockui` lines). An error is a warning. */
    fun dismissLine(outcome: String): String = (if (outcome == DISMISS_ERROR) RideDiagnostics.WARN else "") + "lockui dismiss $outcome"
}
