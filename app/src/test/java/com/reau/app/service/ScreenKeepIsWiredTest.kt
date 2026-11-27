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
    fun `a change of the three settings reaches a running projection`() {
        val main = source("MainActivity.kt")
        val refit = main.substringAfter("val refit = LiveTuning.steps(before, after).isNotEmpty()").substringBefore("if (after.useLocation")
        for (field in listOf("screenKeep", "screenKeepMode", "screenKeepLockUi")) assertTrue(field, "after.$field != before.$field" in refit)
    }

    @Test
    fun `the screen is lit with a dim wake lock that wakes it, always with a timeout, and never with KEEP_SCREEN_ON`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertTrue("PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP" in keeper)
        assertTrue("the deprecation says why", "@Suppress(\"DEPRECATION\") //" in keeper)
        assertEquals("one acquire, with its timeout", 1, Regex("""\.acquire\(""").findAll(keeper).count())
        assertTrue("held.acquire(if (trigger == ScreenKeepPolicy.Trigger.SCREEN_OFF) SCREEN_OFF_LOCK_MS else TIMER_LOCK_MS)" in keeper)
        assertTrue("SCREEN_OFF_LOCK_MS = 10_000L" in keeper && "TIMER_LOCK_MS = 5_000L" in keeper)
        // The function of the switch never asks for it; only the manual mode of the button does (ManualLock).
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
        val off = keeper.substringAfter("private fun turnOff()").substringBefore("private fun screenOff()")
        val steps = listOf("app.unregisterReceiver(heard)", "cancelTimer()", "release()", "ScreenKeepFront.arm(false)", "main.removeCallbacks(it)")
        for (step in steps + "ScreenKeepPolicy.OFF_LINE") assertTrue(step, step in off)
    }

    @Test
    fun `it lights in a pocket too, so no sensor is read and the light is direct`() {
        val keeper = source("service/ScreenKeeper.kt")
        for (word in listOf("Sensor", "proximity", "PROBE_MS", "ProximityRead", "probe", "generation")) assertFalse(word, word in keeper)
        val policy = source("service/ScreenKeepPolicy.kt")
        for (word in listOf("proximity", "covered", "SKIP", "NEAR_CM")) assertFalse(word, word in policy)
        val ask = keeper.substringAfter("private fun ask(").substringBefore("private fun decide(")
        assertTrue(ask, "if (decide(trigger) == ScreenKeepPolicy.Decision.WAKE) wake(trigger)" in ask)
    }

    @Test
    fun `the service never starts an activity, it only arms the app's own, and says in the log when that one was not in front`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertFalse("startActivity" in keeper || "PendingIntent" in keeper || "fullScreenIntent" in keeper || "SYSTEM_ALERT_WINDOW" in keeper)
        assertTrue("ScreenKeepFront.arm(lockUi)" in keeper.substringAfter("private fun turnOn("))
        assertTrue("ScreenKeepFront.arm(lockUi)" in keeper.substringAfter("private fun screenOff()"))
        assertTrue("ScreenKeepFront.arm(false)" in keeper.substringAfter("private fun turnOff()").substringBefore("val heard"))
        val check = keeper.substringAfter("private fun checkFront()").substringBefore("private fun power()")
        assertTrue(check, "ScreenKeepPolicy.frontLine(ScreenKeepFront.resumed, locked)?.let(log)" in check)
        assertTrue(check, "main.postDelayed(check, FRONT_CHECK_MS)" in check && "isKeyguardLocked" in check)
        assertTrue("FRONT_CHECK_MS = 1_500L" in keeper)
        assertTrue("it is asked after the wake lock is taken, and only with the choice on", "if (lockUi) checkFront()" in keeper.substringAfter("held.acquire("))
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
    }

    @Test
    fun `the screen turning on lets the lock go at once, and the rider's pause is asked at every decision and ends with the projection`() {
        val keeper = source("service/ScreenKeeper.kt")
        val on = keeper.substringAfter("private fun screenOn()").substringBefore("private fun armTimer()")
        assertTrue(on, "lock?.takeIf { it.isHeld }?.release()" in on)
        assertTrue("pausedByRider = ScreenKeepFront.paused" in keeper.substringAfter("private fun decide("))
        assertTrue("ScreenKeepFront.clearPause()" in keeper.substringAfter("private fun turnOff()").substringBefore("val heard"))
        val over = source("ui/LockOverHost.kt")
        val pause = over.substringAfter("fun pauseByRider()").substringBefore("private fun locked()")
        assertTrue(pause, "RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.PAUSED_LINE)" in pause && "ScreenKeepFront.pauseByRider()" in pause)
        assertTrue("TextButton(onClick = host::pauseByRider)" in over && "R.string.screen_keep_lock_pause" in over)
        assertTrue("a touch the button consumes does not unlock", "awaitFirstDown()" in over && "requireUnconsumed = false" !in over)
        assertTrue("the unlock by another way leaves the screen of the function", "override fun onReceive(context: Context, intent: Intent) {\n            over = false" in over)
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
    fun `the manual mode puts KEEP_SCREEN_ON and takes it off, hides and gives back the bars, and logs both ends`() {
        val manual = source("ui/ManualLock.kt")
        val enter = manual.substringAfter("fun enter()").substringBefore("/** Out, for")
        assertTrue(enter, "activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in enter)
        assertTrue(enter, "bars(hidden = true)" in enter && "ScreenKeepManual.available(" in enter)
        assertTrue(enter, "RideDiagnostics.log(ScreenKeepPolicy.TAG, it)" in enter && "Intent.ACTION_SCREEN_OFF" in enter)
        val exit = manual.substringAfter("fun exit(reason: ScreenKeepManual.Exit)").substringBefore("private fun bars(")
        assertTrue(exit, "activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in exit && "bars(hidden = false)" in exit)
        assertTrue(exit, "RideDiagnostics.log(ScreenKeepPolicy.TAG, it)" in exit && "back?.remove()" in exit && "activity.unregisterReceiver(it)" in exit)
        assertTrue("the projection stopping ends it", "if (it.phase == Phase.STOPPED) exit(ScreenKeepManual.Exit.PROJECTION)" in manual)
        assertTrue("Back and the power button end it", "exit(ScreenKeepManual.Exit.BACK)" in manual && "exit(ScreenKeepManual.Exit.SCREEN_OFF)" in manual)
        val over = source("ui/LockOverHost.kt")
        assertTrue("the same screen, not another one", "if (over || manual.active) LockOverScreen(this@LockOverHost)" in over)
        assertTrue("the swipe leaves", "host.manual.exit(ScreenKeepManual.Exit.SWIPE)" in over && "ScreenKeepManual.swipedUp(total, threshold)" in over)
        assertTrue("the text changes and the pause button is not there", "R.string.screen_keep_lock_swipe_exit" in over && "if (!manual) {" in over)
        assertTrue("manual.attach()" in over && "fun enterManual() = manual.enter()" in over)
    }

    @Test
    fun `the button is on the main screen only with the projection on, outlined, and reaches the manual mode`() {
        val screen = source("ui/MainScreen.kt")
        assertTrue("under the bridge card", "ModeButtons(p, extras)" in screen)
        val button = screen.substringAfter("private fun ColumnScope.ModeButtons(").substringBefore("/** One line of the foot")
        assertTrue(button, "if (ScreenKeepManual.available(p.phase != Phase.STOPPED)) {" in button)
        assertTrue(button, "GhostButton(" in button && "R.string.action_lock_screen" in button && "extras.onLockScreen" in button)
        assertFalse("not the gold button", "PrimaryPowerButton" in button)
        assertTrue("onLockScreen = { lockOver.enterManual() }" in source("MainActivity.kt"))
        assertTrue("val onLockScreen: () -> Unit = {}" in source("ui/UiState.kt"))
    }
}
