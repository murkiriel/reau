package com.reau.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What REau's proximity lock (0.3.99 test build) shares between the activity that holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` ([com.reau.app.ui.ProximityHold]) and the two
 * things that watch the display going off: [ScreenKeeper] (which would light it again) and [com.reau.app.ui.ScreenLockMode] (which would end the mode). With the lock held and the
 * sensor covered (a pocket) the display goes off and the phone does not lock; that off is the lock's own, and neither of them is to answer it. [ScreenKeepPolicy.proximityCovers]
 * decides, with no Android in it; this holds only the state it is given. Written and read on the main thread, `@Volatile` for the odd reader.
 */
internal object ProximityLock {
    private val heldFlow = MutableStateFlow(false)

    /** Whether the proximity wake lock is held now (set by [ProximityHold][com.reau.app.ui.ProximityHold] right after the acquire, cleared with the release, whatever the way). */
    val held: StateFlow<Boolean> = heldFlow.asStateFlow()

    /** The proximity sensor's last reading: true covered, false uncovered, null not known (no sensor, or none since the lock was taken). */
    @Volatile var near: Boolean? = null
        private set

    /** When the sensor last changed (or the lock was taken), on the clock of [now] given to the calls. */
    @Volatile private var changedAtMs = 0L

    /** The lock was taken at [nowMs]: nothing is known of the sensor yet. */
    fun hold(nowMs: Long) {
        near = null
        changedAtMs = nowMs
        heldFlow.value = true
    }

    /** The lock was let go (or never taken). */
    fun letGo() {
        heldFlow.value = false
        near = null
    }

    /** The sensor read [isNear] at [nowMs]; true when that is a change. */
    fun sensor(isNear: Boolean, nowMs: Long): Boolean {
        if (near == isNear) return false
        near = isNear
        changedAtMs = nowMs
        return true
    }

    /** A reading of the sensor, [value] with its [maxRange]: anything under the range is covered (the way the system's own dialer reads it). */
    fun isNear(value: Float, maxRange: Float): Boolean = value < maxRange

    /**
     * Whether a display that is off since [displayOffSinceMs] is off because of the lock, at [nowMs] ([interactive]: `PowerManager.isInteractive` now); see
     * [ScreenKeepPolicy.proximityCovers].
     */
    fun covers(interactive: Boolean, displayOffSinceMs: Long, nowMs: Long): Boolean =
        ScreenKeepPolicy.proximityCovers(
            heldFlow.value, interactive, near, minOf(nowMs - displayOffSinceMs, nowMs - changedAtMs).coerceAtLeast(0L),
        )
}
