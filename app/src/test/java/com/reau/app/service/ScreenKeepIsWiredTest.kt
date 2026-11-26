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
        for (file in listOf("service/ScreenKeeper.kt", "ui/ScreenKeepActivity.kt")) {
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
        val steps = listOf("app.unregisterReceiver(heard)", "cancelTimer()", "probe?.cancel()", "release()", "ScreenKeepActivity.close()")
        for (step in steps + "ScreenKeepPolicy.OFF_LINE") assertTrue(step, step in off)
    }

    @Test
    fun `the proximity sensor is read only at the moment of lighting, by a listener that gives itself up`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertEquals("one registration, in the one-reading class", 1, Regex("""registerListener\(""").findAll(keeper).count())
        assertTrue("PROBE_MS = 400L" in keeper)
        val read = keeper.substringAfter("private class ProximityRead")
        assertTrue("main.postDelayed(timeout, PROBE_MS)" in read && "manager?.unregisterListener(this)" in read)
        val ask = keeper.substringAfter("private fun ask(").substringBefore("private fun decide(")
        assertTrue("the sensor is asked after the first look at the decision", ask.indexOf("NOTHING") in 0 until ask.indexOf("ProximityRead("))
        assertTrue("the skip is said in the log", "log(ScreenKeepPolicy.SKIP_LINE)" in ask)
    }

    @Test
    fun `the lock screen choice tries the activity and the log says if Android showed it or refused it`() {
        val keeper = source("service/ScreenKeeper.kt")
        val show = keeper.substringAfter("private fun showLockUi()").substringBefore("private fun power()")
        assertTrue("startActivity" in show && "FLAG_ACTIVITY_NEW_TASK" in show)
        for (line in listOf("\"lockui error=\${e.javaClass.simpleName}\"", "\"lockui shown\"", "\"lockui blocked\"")) assertTrue(line, line in show)
        assertTrue("onResume is what it checks", "ScreenKeepActivity.resumes > before" in show)
        val activity = source("ui/ScreenKeepActivity.kt")
        assertTrue("resumes++" in activity.substringAfter("override fun onResume()").substringBefore("override fun onPause()"))
        assertTrue("setShowWhenLocked(true)" in activity && "setTurnScreenOn(true)" in activity)
        assertTrue("requestDismissKeyguard" in activity && "DIM_AFTER_MS = 5_000L" in activity && "screenBrightness = value" in activity)
        assertTrue("it goes when the phone is unlocked another way", "Intent.ACTION_USER_PRESENT" in activity)
    }

    @Test
    fun `the activity is in the manifest over the lock screen, in a task of its own, out of the recents, and no permission was added for it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("android:name=\".ui.ScreenKeepActivity\"").substringBefore("/>")
        val attributes = listOf(
            "android:showWhenLocked=\"true\"", "android:turnScreenOn=\"true\"", "android:excludeFromRecents=\"true\"", "android:exported=\"false\"",
            "android:taskAffinity=\"\${applicationId}.screenkeep\"",
        )
        for (attribute in attributes) assertTrue(attribute, attribute in activity)
        assertEquals("WAKE_LOCK was already there", 1, Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count())
        assertFalse("no permission to draw over other apps", "SYSTEM_ALERT_WINDOW" in manifest)
    }

    @Test
    fun `the log lines go under the screen-keep tag and no thread was added`() {
        val keeper = source("service/ScreenKeeper.kt")
        assertTrue("RideDiagnostics.log(ScreenKeepPolicy.TAG, it)" in keeper)
        assertFalse("Thread(" in keeper || "Executors" in keeper)
        assertTrue("Handler(Looper.getMainLooper())" in keeper)
    }
}
