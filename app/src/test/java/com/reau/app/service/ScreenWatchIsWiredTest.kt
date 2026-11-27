package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the 0.3.99 log of the phone's real lock is fed from and what it asks of Android (the places need a Context, an Activity, a DisplayManager and a SensorManager, as in the other
 * "...IsWired" tests, so these are of the text of the source). What is said and when is held by LockLogTest, ScreenTraceTest and ScreenLockLogTest.
 */
class ScreenWatchIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun code(path: String): String {
        val comment = Regex("""^\s*(\*|//|/\*)""")
        return source(path).lines().filterNot { comment.containsMatchIn(it) }.joinToString("\n")
    }

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    private val keeper = code("service/ScreenKeeper.kt")
    private val mode = code("ui/ScreenLockMode.kt")
    private val watch = code("service/ScreenWatch.kt")
    private val over = code("ui/LockOverHost.kt")
    private val hold = code("ui/ProximityHold.kt")

    @Test
    fun `the keeper begins the watch with its receiver and ends it with it, and the watch is on whenever the keeper is`() {
        val on = keeper.between("private fun turnOn()", "private fun watchTheDisplay()")
        assertTrue(on, "ScreenWatch.begin(app, ScreenWatch.SOURCE_KEEPER)" in on)
        assertTrue("after the receiver is registered", on.indexOf("ContextCompat.registerReceiver(") < on.indexOf("ScreenWatch.begin("))
        val off = keeper.between("private fun turnOff()", "private fun screenOff(")
        assertTrue(off, "ScreenWatch.end(ScreenWatch.SOURCE_KEEPER)" in off)
        assertTrue("only when it was on", off.indexOf("val heard = receiver ?: return") < off.indexOf("ScreenWatch.end("))
        assertTrue("the lock screen is watched with no condition on REau's own lock mode", "ScreenWatch.SOURCE_LOCK" !in keeper)
    }

    @Test
    fun `the keeper's receiver hears the screen off, on and the unlock, and tells the watch of each, but the unlock is not a screen change`() {
        val on = keeper.between("private fun turnOn()", "private fun watchTheDisplay()")
        assertTrue(on, "addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)" in on)
        val receiver = on.between("override fun onReceive(context: Context, intent: Intent) {", "receiver = heard")
        assertTrue(receiver, "ScreenWatch.screenEvent(intent.action)" in receiver)
        assertTrue(receiver, "if (intent.action != Intent.ACTION_USER_PRESENT) seen(intent.action == Intent.ACTION_SCREEN_OFF, ScreenKeepPolicy.Via.BROADCAST)" in receiver)
    }

    @Test
    fun `the keeper's display listener and its 5 s tick tell the watch, before they act`() {
        val listener = keeper.between("override fun onDisplayChanged(displayId: Int) {", "displayListener = listener")
        assertTrue(listener, "ScreenWatch.displayChanged()" in listener && listener.indexOf("ScreenWatch.displayChanged()") < listener.indexOf("seen(displayOff()"))
        val tick = keeper.between("private fun armSafety()", "private fun turnOff()")
        assertTrue(tick, "ScreenWatch.look()" in tick && tick.indexOf("ScreenWatch.look()") < tick.indexOf("seen(displayOff(), ScreenKeepPolicy.Via.TICK)"))
        assertEquals("the keeper reads no lock screen state of its own besides the one look after a light", 1, Regex("""isKeyguardLocked""").findAll(keeper).count())
    }

    @Test
    fun `the keeper counts its wakes for the summary, and its wake lock failures are warnings`() {
        val wake = keeper.between("private fun wake(reason: String)", "private fun checkFront()")
        assertTrue(wake, "runCatching { held.acquire(LOCK_MS) }.onFailure { log(LockLog.warnLine(\"wake lock acquire\", it)) }.isFailure) return" in wake)
        assertTrue("counted after the acquire went through", wake.indexOf("held.acquire(LOCK_MS)") < wake.indexOf("ScreenWatch.wake()"))
        assertTrue(wake, "LockLog.warnLine(\"wake lock create\", it)" in wake)
        val release = Regex("""LockLog\.warnLine\("wake lock release", it\)""").findAll(keeper).count()
        assertEquals("both releases (the screen on, the keeper off) say when they fail", 2, release)
        assertTrue(keeper, "LockLog.warnLine(\"stop listening to the screen\", it)" in keeper && "LockLog.warnLine(\"listening to the screen\", it)" in keeper)
        assertTrue(keeper, "LockLog.warnLine(\"display listener\", it)" in keeper && "LockLog.unavailableLine(\"display manager\")" in keeper)
    }

    @Test
    fun `the lock mode begins the watch with its window as it goes in, ends it as it goes out, and a destroyed activity ends it too`() {
        val enter = mode.between("fun enter(entry: ScreenLock.Entry", "fun resumed()")
        assertTrue(enter, "ScreenWatch.window = ::windowReading" in enter && "ScreenWatch.begin(activity, ScreenWatch.SOURCE_LOCK)" in enter)
        assertTrue("the window is lent before the first snapshot", enter.indexOf("ScreenWatch.window =") < enter.indexOf("ScreenWatch.begin("))
        assertTrue("after the lock and the flag are taken, so the first snapshot is true", enter.indexOf("keep.want(SOURCE)") < enter.indexOf("ScreenWatch.begin(") && enter.indexOf("proximity.acquire(") < enter.indexOf("ScreenWatch.begin("))
        val exit = mode.between("fun exit(reason: ScreenLock.Exit)", "fun dim(on: Boolean")
        assertTrue(exit, "ScreenWatch.end(ScreenWatch.SOURCE_LOCK)" in exit)
        assertTrue("onDestroy", "ScreenWatch.end(ScreenWatch.SOURCE_LOCK)" in mode.between("override fun onDestroy(owner: LifecycleOwner)", "})"))
        assertEquals("what it counts it begins at the mode's start", 1, Regex("""ScreenLockLog\.Counters\(SystemClock\.elapsedRealtime\(\), ScreenWatch\.wakes, ScreenWatch\.keyguardLocks\)""").findAll(enter).count())
    }

    @Test
    fun `the lock mode's display listener and its SCREEN_OFF receiver tell the watch`() {
        val watchDisplay = mode.between("private fun watchTheDisplay()", "private fun lookAtTheDisplay(")
        assertTrue(watchDisplay, "ScreenWatch.displayChanged()" in watchDisplay)
        assertTrue("it no longer writes its own display line: one line for the change, whoever heard it first", "log(change" !in watchDisplay)
        val receiver = mode.between("override fun onReceive(context: Context, intent: Intent) {", "screenOff = off")
        assertTrue(receiver, "ScreenWatch.screenEvent(intent.action)" in receiver && receiver.indexOf("ScreenWatch.screenEvent(") < receiver.indexOf("exit(ScreenLock.Exit.SCREENOFF)"))
        assertTrue("its registrations say when they fail", "LockLog.warnLine(\"listening to the screen going off\", it)" in mode && "LockLog.warnLine(\"display listener\", it)" in mode)
        assertTrue(mode, "LockLog.warnLine(\"stop listening to the screen going off\", it)" in mode && "LockLog.unavailableLine(\"display manager\")" in mode)
    }

    @Test
    fun `the unlock receiver of the screen over the lock screen tells the watch, so the unlock has its cause`() {
        val receiver = over.between("private val unlocked = object : BroadcastReceiver() {", "private var dimTrack")
        assertTrue(receiver, "ScreenWatch.screenEvent(intent.action)" in receiver && "over = false" in receiver)
        assertTrue("Intent.ACTION_USER_PRESENT" in over)
    }

    @Test
    fun `the watch reads the lock screen, the secure state, the interactive state and the display, and all of it on the main thread with no new thread`() {
        assertTrue(watch, "keyguard?.isKeyguardLocked" in watch && "keyguard?.isDeviceSecure" in watch)
        assertTrue(watch, "getSystemService(PowerManager::class.java)?.isInteractive" in watch)
        assertTrue(watch, "getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state" in watch)
        assertTrue("the sensor's last reading is read, the sensor is not registered here", "near = ProximityLock.near" in watch && "SensorManager" !in watch)
        assertFalse("no thread, no executor, no coroutine of its own", "Thread(" in watch || "Executors" in watch || "launch" in watch)
        assertTrue("Handler(Looper.getMainLooper())" in watch)
        for (perFrame in listOf("Choreographer", "onDraw", "doFrame", "FrameMetrics")) assertFalse(perFrame, perFrame in watch)
    }

    @Test
    fun `the snapshot is every minute, written at once as a source begins, and stops with the last source`() {
        val begin = watch.between("fun begin(context: Context, source: String) {", "fun end(")
        assertTrue(begin, "snapshot()" in begin && "if (ticker == null) schedule()" in begin)
        val schedule = watch.between("private fun schedule()", "private fun snapshot()")
        assertTrue(schedule, "main.postDelayed(next, LockLog.SNAPSHOT_MS)" in schedule && "if (!trace.active) return@Runnable" in schedule && "schedule()" in schedule)
        val end = watch.between("fun end(source: String) {", "fun screenEvent(")
        assertTrue(end, "if (!trace.end(source)) return" in end && "main.removeCallbacks(it)" in end && "ticker = null" in end)
        assertTrue("the lock mode's window is let go with it", "if (source == SOURCE_LOCK) window = null" in end)
        val snapshot = watch.between("private fun snapshot()", "private fun read()")
        for (part in listOf("ProximityLock.held.value", "ProjectionService.projection.value", "projection.phase.name", "projection.dash.name", "battery(context)", "window?.invoke()", "LockLog.snapshotLine(")) {
            assertTrue(part, part in snapshot)
        }
        assertEquals(60_000L, LockLog.SNAPSHOT_MS)
    }

    @Test
    fun `the battery is read from the sticky broadcast with no receiver and no permission`() {
        val battery = watch.between("private fun battery(context: Context)", "private fun <T> guarded")
        assertTrue(battery, "context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))" in battery)
        for (extra in listOf("EXTRA_LEVEL", "EXTRA_SCALE", "EXTRA_PLUGGED", "EXTRA_TEMPERATURE")) assertTrue(extra, extra in battery)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (permission in listOf("BATTERY_STATS", "DUMP", "READ_LOGS", "BODY_SENSORS")) assertFalse(permission, permission in manifest)
        for (file in listOf("service/ScreenWatch.kt", "service/ScreenKeeper.kt", "ui/ScreenLockMode.kt", "ui/LockOverHost.kt", "ui/ProximityHold.kt")) {
            assertFalse("$file: no telephony, a call is told only by the focus and the pause", "TelephonyManager" in code(file))
        }
    }

    @Test
    fun `a failed read is a warning said once per watch, and a manager that is missing is said too`() {
        assertTrue(watch, "runCatching(block).onFailure { warnOnce(LockLog.warnLine(what, it)) }.getOrNull()" in watch)
        assertTrue(watch, "if (said.add(line)) RideDiagnostics.log(LockLog.TAG, line)" in watch)
        assertTrue(watch, "if (trace.begin(source, now())) said.clear()" in watch)
        assertTrue(watch, "warnOnce(LockLog.unavailableLine(\"keyguard manager\"))" in watch)
        assertTrue("RideDiagnostics.log(LockLog.TAG, it)" in watch)
    }

    @Test
    fun `the result of the dismiss of the lock screen is in the log, each way it can come`() {
        val unlock = over.between("fun unlock() {", "fun dim(on: Boolean")
        for (outcome in listOf("DISMISS_ASKED", "DISMISS_SUCCEEDED", "DISMISS_CANCELLED", "DISMISS_ERROR")) assertTrue(outcome, "LockLog.dismissLine(LockLog.$outcome)" in unlock)
        assertTrue(unlock, "override fun onDismissSucceeded() {" in unlock && "override fun onDismissCancelled() {" in unlock && "override fun onDismissError() {" in unlock)
        val succeeded = unlock.between("override fun onDismissSucceeded() {", "override fun onDismissCancelled()")
        assertTrue("the success is the cause of the unlock", "ScreenWatch.dismissed()" in succeeded && "over = false" in succeeded)
        val cancelled = unlock.between("override fun onDismissCancelled() {", "override fun onDismissError()")
        assertFalse("a cancelled prompt leaves the screen alive, to be touched again", "over = false" in cancelled)
        assertTrue("a failure to ask at all", "}.onFailure {" in unlock && "LockLog.warnLine(\"dismiss of the lock screen\", it)" in unlock)
        assertTrue("no keyguard manager", "LockLog.unavailableLine(\"keyguard manager\")" in unlock)
        assertTrue("the receiver's unregister failure", "LockLog.warnLine(\"stop listening to the unlock\", it)" in over && "LockLog.warnLine(\"listening to the unlock\", it)" in over)
    }

    @Test
    fun `the brightness changes are written by the lock mode and by the screen over the lock screen, each with its reason`() {
        val dim = mode.between("fun dim(on: Boolean, reason: String? = null) {", "private fun windowReading()")
        assertTrue(dim, "dimTrack.set(on, reason, SystemClock.elapsedRealtime())?.let(::log)" in dim)
        assertTrue("a new track for each mode", "dimTrack = ScreenLockLog.DimTrack()" in mode.between("fun enter(entry: ScreenLock.Entry", "fun resumed()"))
        val screen = code("ui/ScreenLockScreen.kt")
        assertTrue(screen, "why = ScreenLockLog.BRIGHT_TOUCH" in screen && "why = ScreenLockLog.BRIGHT_DRAG" in screen && "mutableStateOf(ScreenLockLog.BRIGHT_ENTER)" in screen)
        assertTrue("the way out is the default reason of coming back", "mode.dim(false)" in screen.between("DisposableEffect(Unit)", "REauTheme"))
        assertTrue(over, "dimTrack.set(on, reason, SystemClock.elapsedRealtime())?.let(::log)" in over && "ScreenLockLog.DimTrack(\"lockui brightness\")" in over)
    }

    @Test
    fun `the track's touches, aborted drags and the drag that unlocked reach the log through the mode`() {
        val screen = code("ui/ScreenLockScreen.kt")
        assertTrue(screen, "mode.slideTouched()" in screen && "onAbort = mode::slideAborted" in screen && "onDone = mode::slideDone" in screen)
        val track = screen.substringAfter("private fun SlideTrack(")
        assertTrue("the farthest the thumb got is kept", "farthest = maxOf(farthest, ScreenLock.progress(dragPx, travel))" in track)
        assertTrue("a drag let go short is said only if the thumb moved", "if (onThumb && farthest > 0f) onAbort(farthest)" in track)
        assertTrue("the drag's time is from the finger down", "val downAtMs = SystemClock.elapsedRealtime()" in track && "onDone(SystemClock.elapsedRealtime() - downAtMs)" in track)
        assertTrue("the touch is said by the mode, grouped", "counters.touches.hit(SystemClock.elapsedRealtime())?.let { log(ScreenLockLog.touchLine(it)) }" in mode)
        assertTrue(mode, "log(ScreenLockLog.abortLine(ScreenLockLog.percent(progress)))" in mode && "counters.abort()" in mode && "log(ScreenLockLog.doneLine(dragMs))" in mode)
    }

    @Test
    fun `the focus, the pause, the resume and the stop of the activity are written while the mode is on`() {
        assertTrue(mode, "if (active) counters.focus(hasFocus, SystemClock.elapsedRealtime())?.let(::log)" in mode)
        assertTrue(mode, "if (active) log(counters.activity(event, SystemClock.elapsedRealtime()))" in mode)
        assertTrue(mode.between("fun resumed()", "fun focusChanged("), "lifecycleSeen(ScreenLockLog.RESUMED)" in mode)
        assertTrue(mode.between("fun paused()", "private fun front()"), "lifecycleSeen(ScreenLockLog.PAUSED)" in mode)
        assertTrue(mode.between("fun stopped()", "fun exit(reason"), "lifecycleSeen(ScreenLockLog.STOPPED)" in mode)
        val main = source("MainActivity.kt")
        val focus = main.between("override fun onWindowFocusChanged(hasFocus: Boolean) {", "override fun onUserInteraction()")
        assertTrue(focus, "super.onWindowFocusChanged(hasFocus)" in focus && "lockMode.focusChanged(hasFocus)" in focus)
    }

    @Test
    fun `the summary is written at the way out, right after manual off, and says what the mode counted`() {
        val exit = mode.between("fun exit(reason: ScreenLock.Exit)", "fun dim(on: Boolean")
        val off = exit.indexOf("state.exit(reason, SystemClock.elapsedRealtime())?.let(::log)")
        val summary = exit.indexOf("log(counters.summary(at, dimTrack.dimMs(at), state.backCount, ScreenWatch.wakes, ScreenWatch.keyguardLocks).line())")
        assertTrue(exit, off >= 0 && summary > off)
        assertTrue("the open stretch of the proximity off is closed at the way out", "counters.proximityOn(SystemClock.elapsedRealtime())" in exit)
        assertTrue("the proximity off is timed from the lock's own off to the display on again", "if (covered) counters.proximityOff(SystemClock.elapsedRealtime())" in mode && "counters.proximityOn(SystemClock.elapsedRealtime())" in mode.between("proximityEdge.on()?.let(::log)", "offCheck"))
    }

    @Test
    fun `the sensor is written at each change with its raw value and the range once, and its failures are warnings`() {
        assertTrue(hold, "lines.hit(SystemClock.elapsedRealtime())?.let { log(ScreenLockLog.sensorLine(near, value, it)) }" in hold && "val lines = ScreenLockLog.Grouper()" in hold && "log(ScreenLockLog.sensorRegisteredLine(sensor.maximumRange))" in hold)
        assertTrue("only a change is written", hold.indexOf("if (ProximityLock.sensor(near, SystemClock.elapsedRealtime())) {") < hold.indexOf("lines.hit("))
        for (what in listOf("power manager", "sensor manager", "proximity sensor", "proximity sensor listener")) assertTrue(what, "LockLog.unavailableLine(\"$what\")" in hold)
        assertTrue(hold, "LockLog.warnLine(\"proximity lock\", it)" in hold && "LockLog.warnLine(\"proximity lock release\", it)" in hold && "LockLog.warnLine(\"proximity sensor listener release\", it)" in hold)
    }

    @Test
    fun `the idle watch says when it could not read the switch or the timeout`() {
        val idle = code("ui/IdleLockWatch.kt")
        assertTrue(idle, "LockLog.warnLine(\"reading the lock mode switch\", it)" in idle && "LockLog.warnLine(\"reading the screen timeout\", it)" in idle)
    }

    @Test
    fun `the state lines have their own tag and no permission was added`() {
        assertEquals("screen-watch", LockLog.TAG)
        assertTrue("RideDiagnostics.log(ScreenLock.TAG, it)" in source("ui/ProximityHold.kt"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("WAKE_LOCK was already there", 1, Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count())
    }
}
