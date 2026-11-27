package com.reau.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.IdleLock
import com.reau.app.service.LockLog
import com.reau.app.service.ProjectionService
import com.reau.app.service.ScreenLock
import com.reau.app.settings.SettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Android side of the idle lock (0.3.97 test build, rules in [IdleLock]): while the switch is on, the dash is ready and REau is in front, the window keeps the screen on
 * ([KeepScreenOn]) and a count of the phone's own screen timeout (`SCREEN_OFF_TIMEOUT`, between 15 s and 10 min) runs from the last touch ([onUserInteraction][touched]);
 * when it runs out REau's lock takes the screen ([ScreenLockMode.enter] by [ScreenLock.Entry.IDLE]) instead of Android turning it off. Anything that stops the conditions
 * (the dash out of "Pronto", the projection off, the switch off, REau out of the front, the lock already on) stops the count and gives the flag back. Main thread.
 */
internal class IdleLockWatch(private val activity: ComponentActivity, private val mode: ScreenLockMode, private val keep: KeepScreenOn) {
    private val main = Handler(Looper.getMainLooper())
    private var switchOn = false
    private var resumed = false
    private var count: IdleLock.Count? = null
    private var expiry: Runnable? = null

    /** From the activity's onPostCreate: reads the switch, follows the lock mode and the projection's phase and dash. */
    fun attach() {
        switchOn = runCatching { SettingsStore.open(activity).load().lockMode }
            .onFailure { RideDiagnostics.log(ScreenLock.TAG, LockLog.warnLine("reading the lock mode switch", it)) }.getOrDefault(false)
        mode.onChange = ::evaluate
        activity.lifecycleScope.launch {
            ProjectionService.projection.map { it.phase to it.dash }.distinctUntilChanged().collect { evaluate() }
        }
    }

    /** The rider changed the switch. */
    fun setSwitch(on: Boolean) {
        switchOn = on
        evaluate()
    }

    /** REau came to the front, or left it. */
    fun resumed(front: Boolean) {
        resumed = front
        evaluate()
    }

    /** Any touch of the rider on the window (the activity's onUserInteraction): the count begins again. */
    fun touched() {
        val running = count ?: return
        running.touch(SystemClock.elapsedRealtime())
        schedule()
    }

    private fun evaluate() {
        val p = ProjectionService.projection.value
        val reason = IdleLock.offReason(switchOn, p.phase, p.dash, resumed, mode.active)
        if (reason == null) start() else stop(reason)
    }

    private fun start() {
        if (count != null) return
        val read = runCatching { systemTimeout() }
            .onFailure { RideDiagnostics.log(ScreenLock.TAG, LockLog.warnLine("reading the screen timeout", it)) }
        val timeout = IdleLock.timeoutMs(read.getOrDefault(IdleLock.DEFAULT_SYSTEM_MS))
        count = IdleLock.Count(timeout, SystemClock.elapsedRealtime())
        keep.want(SOURCE)
        RideDiagnostics.log(ScreenLock.TAG, IdleLock.onLine(timeout))
        schedule()
    }

    private fun stop(reason: String) {
        expiry?.let { main.removeCallbacks(it) }
        expiry = null
        if (count == null) return
        count = null
        keep.release(SOURCE)
        RideDiagnostics.log(ScreenLock.TAG, IdleLock.offLine(reason))
    }

    /** The phone's own screen timeout, in ms (`Settings.System.SCREEN_OFF_TIMEOUT`), [IdleLock.DEFAULT_SYSTEM_MS] when the phone says nothing. */
    private fun systemTimeout(): Int =
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, IdleLock.DEFAULT_SYSTEM_MS)

    private fun schedule() {
        expiry?.let { main.removeCallbacks(it) }
        val running = count ?: return
        val next = Runnable { fire() }
        expiry = next
        main.postDelayed(next, running.msUntilExpiry(SystemClock.elapsedRealtime()))
    }

    /** The count ran out: REau's lock takes the screen (which stops the count, as `locked`). Not yet, if a touch moved it. */
    private fun fire() {
        expiry = null
        val running = count ?: return
        if (running.expired(SystemClock.elapsedRealtime())) mode.enter(ScreenLock.Entry.IDLE) else schedule()
    }

    private companion object {
        const val SOURCE = "idle"
    }
}
