package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where "Tela acesa com o celular bloqueado" is fed from and what it asks of Android. These are of the text of the source (the places need a Context, a
 * PowerManager, a SensorManager and an Activity, as in the other "...IsWired" tests); what the function decides is held by ScreenKeepPolicyTest.
 */
class ScreenKeepIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the service starts it when the locks are taken, hands it the settings changes and lets it go with the projection`() {
        val service = source("service/ProjectionService.kt")
        assertTrue("screenKeeper = ScreenKeeper(this, { SettingsStore.open(this).load() })" in service)
        val locks = service.substringAfter("locks.acquire()").substringBefore("lookAtTheDevice()")
        assertTrue(locks, "screenKeeper.sync()" in locks)
        val refit = service.substringAfter("ACTION_REFIT -> worker.execute {").substringBefore("// A restart by the system")
        assertTrue(refit, "screenKeeper.sync()" in refit)
        val release = service.substringAfter("override fun release()").substringBefore("stopSelf(command)")
        assertTrue(release, "screenKeeper.stop()" in release)
        assertTrue("\"screen-keep\" to { screenKeeper.stop() }" in service)
    }

    @Test
    fun `a change of the one switch reaches a running projection`() {
        val main = source("MainActivity.kt")
        val refit = main.substringAfter("val refit = LiveTuning.steps(before, after).isNotEmpty()").substringBefore("if (after.useLocation")
        assertTrue(refit, "after.lockMode != before.lockMode" in refit)
    }

    @Test
    fun `the screen is lit with a bright wake lock that wakes it and keeps it on after, always with a timeout, and never with KEEP_SCREEN_ON`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertTrue("PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE" in keeper)
        assertFalse("not the dim one", "SCREEN_DIM_WAKE_LOCK" in keeper.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n"))
        assertTrue("the deprecation says why", "@Suppress(\"DEPRECATION\") //" in keeper)
        assertEquals("one acquire, with its timeout", 1, Regex("""\.acquire\(""").findAll(keeper).count())
        assertTrue("held.acquire(LOCK_MS)" in keeper)
        assertTrue("LOCK_MS = 10_000L" in keeper && "TIMER" !in keeper)
        // The function of the switch never asks for it; only the lock of the button does (ScreenLockMode).
        for (file in listOf("service/ScreenKeeper.kt", "ui/LockOverHost.kt")) {
            val comment = Regex("""^\s*(\*|//|/\*)""")
            val code = source(file).lines().filterNot { comment.containsMatchIn(it) }.joinToString("\n")
            assertFalse("$file: KEEP_SCREEN_ON", "KEEP_SCREEN_ON" in code)
        }
    }

    @Test
    fun `it listens to the screen going off and on, and lets everything go when it stops`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertTrue("Intent.ACTION_SCREEN_OFF" in keeper && "Intent.ACTION_SCREEN_ON" in keeper)
        val off = keeper.substringAfter("private fun turnOff()").substringBefore("private fun screenOff(")
        val steps = listOf("app.unregisterReceiver(heard)", "unregisterDisplayListener(it)", "displayListener = null", "safety = null", "release()", "ScreenKeepFront.arm(false)", "main.removeCallbacks(it)", "frontCheck = null")
        for (step in steps + "ScreenKeepPolicy.OFF_LINE") assertTrue(step, step in off)
    }

    @Test
    fun `it lights in a pocket too, so it reads no sensor of its own and the light is direct, except while REau's proximity lock holds the display off`() {
        val keeper = source("service/ScreenKeeper.kt")
        for (word in listOf("SensorManager", "Sensor.", "PROBE_MS", "ProximityRead", "probe", "generation")) assertFalse(word, word in keeper)
        val policy = source("service/ScreenKeepPolicy.kt")
        for (word in listOf("covered(", "SKIP", "NEAR_CM", "SensorManager")) assertFalse(word, word in policy)
        val ask = keeper.substringAfter("private fun ask(").substringBefore("private fun holdBackForProximity(")
        assertTrue(ask, "if (why == null) wake(reason) else log(ScreenKeepPolicy.nothingLine(why))" in ask)
        // The one exception (0.3.99) is the lock's own off, read from the shared state and nothing else (ProximityIsWiredTest holds the rest).
        assertTrue("ProximityLock.held.value && ProximityLock.covers(power().isInteractive, offSinceMs, now())" in keeper)
    }

    @Test
    fun `the service never starts an activity, it only arms the app's own, and says in the log when that one was not in front`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertFalse("startActivity" in keeper || "PendingIntent" in keeper || "fullScreenIntent" in keeper || "SYSTEM_ALERT_WINDOW" in keeper)
        assertTrue("ScreenKeepFront.arm(true)" in keeper.substringAfter("private fun turnOn("))
        assertTrue("ScreenKeepFront.arm(true)" in keeper.substringAfter("private fun screenOff("))
        assertTrue("the one switch decides", "if (settings().lockMode) turnOn() else turnOff()" in keeper && "if (!settings().lockMode) return turnOff()" in keeper)
        assertTrue("ScreenKeepFront.arm(false)" in keeper.substringAfter("private fun turnOff()").substringBefore("val heard"))
        val check = keeper.substringAfter("private fun checkFront()").substringBefore("private fun power()")
        assertTrue(check, "ScreenKeepPolicy.frontLine(ScreenKeepFront.resumed, locked)?.let(log)" in check)
        assertTrue(check, "main.postDelayed(check, FRONT_CHECK_MS)" in check && "isKeyguardLocked" in check)
        assertTrue("FRONT_CHECK_MS = 1_500L" in keeper)
        assertTrue("it is asked after the wake lock is taken", "        checkFront()" in keeper.substringAfter("held.acquire("))
    }

    @Test
    fun `the app's activity is shown over the lock screen from its own screen, with showWhenLocked and turnScreenOn only while armed`() {
        val over = source("ui/LockOverHost.kt")
        val apply = over.substringAfter("private fun apply(armed: Boolean)").substringBefore("/** The activity is resumed")
        assertTrue(apply, "activity.setShowWhenLocked(armed)" in apply && "activity.setTurnScreenOn(armed)" in apply)
        assertTrue(apply, "RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.ARMED_LINE)" in apply)
        assertTrue("it follows the arming for as long as the activity lives", "ScreenKeepFront.armed.collect { apply(it) }" in over)
        val resumed = over.substringAfter("fun resumed()").substringBefore("fun paused()")
        assertTrue(resumed, "ScreenKeepFront.resumed = true" in resumed && "over = ScreenKeepFront.armed.value && locked()" in resumed)
        assertTrue(resumed, "ScreenKeepPolicy.SHOWN_LINE" in resumed)
        assertTrue("ScreenKeepFront.resumed = false" in over.substringAfter("fun paused()").substringBefore("private fun locked()"))
        assertTrue("requestDismissKeyguard" in over && "DIM_AFTER_MS = 5_000L" in over && "awaitFirstDown(" in over)
        assertTrue("it goes when the phone is unlocked another way", "Intent.ACTION_USER_PRESENT" in over)
        val main = source("MainActivity.kt")
        assertTrue("private val lockOver = com.reau.app.ui.LockOverHost(this)" in main && "lockOver.attach()" in main)
        assertTrue("the layer goes on after setContent", "override fun onPostCreate" in main && "lockOver.attach()" in main.substringAfter("override fun onPostCreate"))
        assertTrue("the layer is added on top, and draws only while over", "activity.addContentView(" in over && "setContent { if (over) LockOverScreen(this@LockOverHost) }" in over)
        assertTrue("lockOver.resumed()" in main.substringAfter("private fun resume()").substringBefore("resumedAtMs"))
        assertTrue("lockOver.paused()" in main.substringAfter("override fun onPause()").substringBefore("super.onPause()"))
        assertFalse("onCreate has no branch of it", "LockOverScreen" in main)
        assertTrue("the button's lock is the other one (ScreenLockMode)", "onLockScreen = { lockMode.enter() }" in main)
    }

    @Test
    fun `the screen turning on lets the lock go at once, and the rider's pause is asked at every decision and ends with the projection`() {
        val keeper = source("service/ScreenKeeper.kt")
        val on = keeper.substringAfter("private fun screenOn()").substringBefore("/** The decision, and the light")
        assertTrue(on, "lock?.takeIf { it.isHeld }?.release()" in on)
        assertTrue("ScreenKeepPolicy.why(receiver != null, lastWakeMs?.let { now() - it }, ScreenKeepFront.paused, proximityNow())" in keeper.substringAfter("private fun ask("))
        assertTrue("ScreenKeepFront.clearPause()" in keeper.substringAfter("private fun turnOff()").substringBefore("val heard"))
        val over = source("ui/LockOverHost.kt")
        val pause = over.substringAfter("fun pauseByRider()").substringBefore("private fun locked()")
        assertTrue(pause, "RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.PAUSED_LINE)" in pause && "ScreenKeepFront.pauseByRider()" in pause)
        assertTrue("TextButton(onClick = host::pauseByRider)" in over && "R.string.screen_keep_lock_pause" in over)
        assertTrue("a touch the button consumes does not unlock", "awaitFirstDown()" in over && "requireUnconsumed = false" !in over)
        assertTrue("the unlock by another way leaves the screen of the function", "override fun onReceive(context: Context, intent: Intent) {\n            over = false" in over)
    }

    @Test
    fun `every touch over the lock screen asks Android again, so a cancelled prompt leaves the screen alive, and a touch brings the brightness back`() {
        val over = source("ui/LockOverHost.kt")
        assertTrue("the anchor is gone", "private fun Modifier.overTouch(" in over)
        val touch = over.substringAfter("private fun Modifier.overTouch(").substringBefore("private const val CLOCK_MS")
        assertTrue(touch, "pointerInput(Unit) {\n    awaitEachGesture {" in touch)
        assertFalse("not the pointer scope that ends after the first touch", "awaitPointerEventScope" in touch)
        assertTrue(touch, "awaitFirstDown()" in touch && "onTouch()" in touch && "host.unlock()" in touch)
        assertTrue("the touch moves the count of the brightness", ".overTouch(host) { touches++ }" in over)
        val effect = over.substringAfter("LaunchedEffect(touches) {").substringBefore("DisposableEffect")
        assertTrue(effect, "host.dim(false, if (touches == 0) ScreenLockLog.BRIGHT_ENTER else ScreenLockLog.BRIGHT_TOUCH)" in effect && "delay(LockOverHost.DIM_AFTER_MS)" in effect && "host.dim(true)" in effect)
    }

    @Test
    fun `the screen of the function draws nothing animated`() {
        val code = source("ui/LockOverHost.kt").lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        for (animated in listOf("animate", "infiniteTransition", "rememberInfiniteTransition", "Animatable", "AnimatedVisibility", "Canvas", "Image(", "painterResource")) {
            assertFalse(animated, animated in code)
        }
    }

    @Test
    fun `there is no separate activity for it, and no permission was added`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("ScreenKeepActivity" in manifest)
        assertFalse(File("src/main/java/com/reau/app/ui/ScreenKeepActivity.kt").exists())
        assertEquals("WAKE_LOCK was already there", 1, Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count())
        assertFalse("no permission to draw over other apps", "SYSTEM_ALERT_WINDOW" in manifest)
        assertFalse("no full-screen intent", "USE_FULL_SCREEN_INTENT" in manifest)
    }

    @Test
    fun `the log lines go under the screen-keep tag and no thread was added`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertTrue("RideDiagnostics.log(ScreenKeepPolicy.TAG, it)" in keeper)
        assertFalse("Thread(" in keeper || "Executors" in keeper)
        assertTrue("Handler(Looper.getMainLooper())" in keeper)
    }

    @Test
    fun `the screen going off or on is read from the default display, with the broadcast and a safety tick as other sources, and nothing but the proximity lock's off depends on isInteractive`() {
        val keeper = source("service/ScreenKeeper.kt")
        val code = keeper.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        // Only the proximity lock's own off asks it (0.3.99), in one place: a real sleep is told from the lock's off by it.
        assertEquals("one place reads isInteractive", 1, Regex("""isInteractive""").findAll(code).count())
        assertTrue("isInteractive" in code.substringAfter("private fun proximityNow()").substringBefore("/** The decision"))
        val watch = keeper.substringAfter("private fun watchTheDisplay()").substringBefore("/** Whether the default display is not on now")
        assertTrue(watch, "manager.registerDisplayListener(listener, main)" in watch && "displayId == Display.DEFAULT_DISPLAY" in watch)
        assertTrue(watch, "seen(displayOff(), ScreenKeepPolicy.Via.LISTENER)" in watch)
        assertTrue("seen(intent.action == Intent.ACTION_SCREEN_OFF, ScreenKeepPolicy.Via.BROADCAST)" in keeper)
        val safety = keeper.substringAfter("private fun armSafety()").substringBefore("private fun turnOff()")
        assertTrue(safety, "seen(displayOff(), ScreenKeepPolicy.Via.TICK)" in safety && "main.postDelayed(next, ScreenKeepPolicy.TICK_MS)" in safety)
        assertTrue(safety, "if (edge.off) {" in safety && "if (retry || proximityNoted || proximityNow()) {" in safety)
        val seen = keeper.substringAfter("private fun seen(").substringBefore("/** The safety net")
        assertTrue(seen, "val line = edge.heard(off, via) ?: return" in seen && "log(line)" in seen && "if (off) screenOff() else screenOn()" in seen)
        val turnOff = keeper.substringAfter("private fun turnOff()").substringBefore("private fun screenOff(")
        assertTrue(turnOff, "unregisterDisplayListener(it)" in turnOff && "safety?.let { main.removeCallbacks(it) }" in turnOff)
        assertTrue("it begins from what the display says now", "edge.reset(displayOff())" in keeper.substringAfter("private fun turnOn("))
    }

    @Test
    fun `the function begins by lighting a screen that is already off, and the tick lights again one that stayed dark, with the reason in the log`() {
        val keeper = source("service/ScreenKeeper.kt")
        val on = keeper.substringAfter("private fun turnOn()").substringBefore("/** The default display's state is the source")
        assertTrue(on, "edge.reset(displayOff())" in on && "if (edge.off) screenOff(ScreenKeepPolicy.REASON_START)" in on)
        assertTrue("the safety tick is armed after", on.indexOf("if (edge.off) screenOff(") in 0 until on.indexOf("armSafety()"))
        val safety = keeper.substringAfter("private fun armSafety()").substringBefore("private fun turnOff()")
        assertTrue(safety, "ScreenKeepPolicy.darkRetry(true, lightAsked, lastWakeMs?.let { now() - it }, darkTries)" in safety)
        assertTrue(safety, "darkTries++" in safety && "ask(ScreenKeepPolicy.REASON_DARK)" in safety)
        val wake = keeper.substringAfter("private fun wake(reason: String)").substringBefore("private fun checkFront()")
        assertTrue(wake, "lightAsked = true" in wake && "ScreenKeepPolicy.wakeLine(ScreenKeepPolicy.secondsOff(offSinceMs, at), reason)" in wake)
        val screenOff = keeper.substringAfter("private fun screenOff(").substringBefore("private fun screenOn()")
        assertTrue(screenOff, "ask(reason)" in screenOff)
        val screenOn = keeper.substringAfter("private fun screenOn()").substringBefore("/** The decision, and the light")
        assertTrue(screenOn, "lightAsked = false" in screenOn && "darkTries = 0" in screenOn)
        val off = keeper.substringAfter("private fun turnOff()").substringBefore("private fun screenOff(")
        assertTrue(off, "lightAsked = false" in off && "darkTries = 0" in off)
    }

    @Test
    fun `a change of the switch while the projection prepares reaches the screen kept lit when it runs`() {
        val service = source("service/ProjectionService.kt")
        val change = service.substringAfter("tellTheRider(lastReported, state)").substringBefore("lastReported = state")
        assertTrue(change, "if (lastReported.phase != Phase.RUNNING && state.phase == Phase.RUNNING) screenKeeper.sync()" in change)
    }

    @Test
    fun `a decision not to light the screen says why in the log`() {
        val ask = source("service/ScreenKeeper.kt").substringAfter("private fun ask(").substringBefore("private fun wake(")
        assertTrue(ask, "ScreenKeepPolicy.why(" in ask && "log(ScreenKeepPolicy.nothingLine(why))" in ask)
        assertTrue(ask, "retry = why == ScreenKeepPolicy.WHY_GAP" in ask)
    }

    @Test
    fun `the intervals are gone, only the light at the screen going off is left`() {
        val keeper = source("service/ScreenKeeper.kt").lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        for (word in listOf("armTimer", "cancelTimer", "fire()", "nextDelayMs", "ScreenKeepMode", "Trigger.TIMER", "intervalSec")) assertFalse(word, word in keeper)
        val policy = source("service/ScreenKeepPolicy.kt").lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        for (word in listOf("nextDelayMs", "ScreenKeepMode", "Trigger", "Decision")) assertFalse(word, word in policy)
        assertTrue("the safety tick and the retry of the gap stay", "armSafety()" in keeper && "if (retry || proximityNoted || proximityNow()) {" in keeper.substringAfter("private fun armSafety()"))
    }
}
