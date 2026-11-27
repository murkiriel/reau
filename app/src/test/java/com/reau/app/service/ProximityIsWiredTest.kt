package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where REau's proximity lock (0.3.99) is taken, let go and heard. These are of the text of the source (the places need a PowerManager, a SensorManager, an Activity and a
 * DisplayManager, as in the other "...IsWired" tests); what is decided is held by ScreenKeepPolicyTest, ProximityLockTest and ScreenLockTest.
 */
class ProximityIsWiredTest {
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

    private val hold = code("ui/ProximityHold.kt")
    private val mode = code("ui/ScreenLockMode.kt")
    private val keeper = code("service/ScreenKeeper.kt")

    @Test
    fun `the lock is the proximity level, one instance that is not reference counted, taken only when the phone supports it`() {
        assertEquals("one place makes it", 1, Regex("""newWakeLock\(""").findAll(hold).count())
        assertTrue("newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOCK_TAG)" in hold)
        assertTrue("it is one instance, kept" , "val taken = lock ?: power.newWakeLock(" in hold && "lock = it" in hold)
        assertTrue("it is not reference counted", "it.setReferenceCounted(false)" in hold)
        assertEquals("one acquire", 1, Regex("""\.acquire\(""").findAll(hold).count())
        val acquire = hold.between("fun acquire(reason: String)", "fun release(")
        val support = acquire.indexOf("power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)")
        assertTrue(acquire, support in 0 until acquire.indexOf("newWakeLock("))
        assertTrue("not held twice", "if (held) return" in acquire && "if (!taken.isHeld) taken.acquire()" in acquire)
        assertTrue("unsupported: said once, and the mode goes on as before", "if (!unsupportedSaid) log(ScreenLock.PROXIMITY_UNSUPPORTED_LINE)" in acquire && "unsupportedSaid = true\n            return" in acquire)
        assertTrue("the state is marked held after the acquire, not before", acquire.indexOf("taken.acquire()") < acquire.indexOf("ProximityLock.hold("))
        assertTrue("it says it", "log(ScreenLock.proximityLine(true, reason))" in acquire)
        assertTrue("RideDiagnostics.log(ScreenLock.TAG, it)" in hold)
        assertFalse("no timeout was added: every way out lets go", Regex("""\.acquire\([^)]""").containsMatchIn(hold))
        assertTrue("the lint rule is silenced with its reason", "@SuppressLint(\"WakelockTimeout\") //" in source("ui/ProximityHold.kt"))
    }

    @Test
    fun `letting go checks isHeld first, clears the shared state even when the release throws, and is the one place that does`() {
        val release = hold.between("fun release(reason: String, waitForFar: Boolean = false)", "private fun listen()")
        assertTrue(release, "if (taken != null && taken.isHeld)" in release)
        assertTrue(release, "taken.release(if (waitForFar) PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY else 0)" in release)
        assertTrue("the sensor goes first", release.indexOf("stopListening()") in 0 until release.indexOf("taken.release("))
        assertTrue(release, "}.onFailure {" in release && release.indexOf("taken.release(") < release.indexOf("ProximityLock.letGo()"))
        assertTrue("letGo is outside the runCatching, so a throw does not skip it", release.indexOf("}.onFailure {") < release.indexOf("ProximityLock.letGo()"))
        assertTrue("it says it only when it was held", "if (wasHeld) log(ScreenLock.proximityLine(false, reason))" in release)
        assertEquals("one release call", 1, Regex("""\.release\(""").findAll(hold).count())
        assertTrue("a failed acquire lets go too", "release(\"failed\")" in hold.between("fun acquire(reason: String)", "fun release("))
    }

    @Test
    fun `the sensor is read only while the lock is held, to tell a covered display, and goes with the lock`() {
        val listen = hold.between("private fun listen()", "private fun stopListening()")
        assertTrue(listen, "Sensor.TYPE_PROXIMITY" in listen && "ProximityLock.isNear(value, sensor.maximumRange)" in listen)
        assertTrue(listen, "sensors.registerListener(reader, sensor, SensorManager.SENSOR_DELAY_NORMAL, main)" in listen)
        assertTrue(listen, "if (ProximityLock.sensor(" in listen && "onSensorChange?.invoke()" in listen)
        assertTrue("it is registered after the lock is taken", hold.indexOf("ProximityLock.hold(") < hold.indexOf("            listen()"))
        assertTrue("unregistered with the lock", "unregisterListener(reader)" in hold.between("private fun stopListening()", "private companion"))
        assertEquals("the keeper reads no sensor of its own", 0, Regex("""SensorManager|Sensor\.""").findAll(keeper).count())
    }

    @Test
    fun `the lock mode takes it when it goes in with REau in front, and again when the app comes back with the mode on`() {
        val enter = mode.between("fun enter(entry: ScreenLock.Entry", "fun resumed()")
        assertTrue(enter, "if (front()) proximity.acquire(ScreenLock.PROXIMITY_ENTER)" in enter)
        assertTrue("last, after the display listener", enter.indexOf("watchTheDisplay()") < enter.indexOf("proximity.acquire("))
        assertTrue(mode, "activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)" in mode)
        val resumed = mode.between("fun resumed()", "fun paused()")
        assertTrue(resumed, "if (active) proximity.acquire(ScreenLock.PROXIMITY_RESUME)" in resumed)
        assertEquals("the only two ways to take it", 2, Regex("""proximity\.acquire\(""").findAll(mode).count())
        val main = source("MainActivity.kt")
        assertTrue("lockMode.resumed()" in main.between("private fun resume()", "resumedAtMs"))
    }

    @Test
    fun `every way out lets the lock go at once, the end of the projection waits for the sensor, and nothing else is a way to take it`() {
        val exit = mode.between("fun exit(reason: ScreenLock.Exit)", "fun dim(on: Boolean")
        assertTrue("first thing after the flag", exit.indexOf("active = false") < exit.indexOf("proximity.release(") && exit.indexOf("proximity.release(") < exit.indexOf("state.exit("))
        assertTrue(exit, "proximity.release(reason.logName, waitForFar = reason == ScreenLock.Exit.PROJECTION)" in exit)
        val paused = mode.between("fun paused()", "private fun front()")
        assertTrue(paused, "proximity.release(ScreenLock.PROXIMITY_PAUSE)" in paused && "waitForFar" !in paused)
        assertTrue("onDestroy", "proximity.release(ScreenLock.PROXIMITY_DESTROY)" in mode.between("override fun onDestroy(owner: LifecycleOwner)", "})"))
        assertEquals("exit, paused, onDestroy: no other release", 3, Regex("""proximity\.release\(""").findAll(mode).count())
        val main = source("MainActivity.kt")
        assertTrue("lockMode.paused()" in main.between("override fun onPause()", "super.onPause()"))
        assertTrue("onStop ends the mode, which lets go", "lockMode.stopped()" in main.between("override fun onStop()", "super.onStop()"))
        val stopped = mode.between("fun stopped()", "fun exit(reason")
        assertTrue(stopped, "exit(if (off) ScreenLock.Exit.SCREENOFF else ScreenLock.Exit.LEFT)" in stopped)
        assertTrue("the projection stopping, the swipe and the screen going off all end by exit()", "exit(ScreenLock.Exit.PROJECTION)" in mode && "exit(ScreenLock.Exit.SCREENOFF)" in mode)
        assertTrue("the swipe", "{ mode.exit(ScreenLock.Exit.SWIPE) }" in code("ui/ScreenLockScreen.kt"))
    }

    @Test
    fun `the display going off with the lock held is told from a real off, and only a real one ends the mode`() {
        val look = mode.between("private fun lookAtTheDisplay()", "private fun bars(")
        assertTrue(look, "ProximityLock.covers(interactive, offSinceMs, SystemClock.elapsedRealtime())" in look)
        assertTrue(look, "activity.getSystemService(PowerManager::class.java)?.isInteractive == true" in look)
        assertTrue(look, "if (!covered) {\n            exit(ScreenLock.Exit.SCREENOFF)" in look)
        assertTrue("the sensor's late event is waited for", "} else if (ProximityLock.near == false) {\n            main.postDelayed(offCheck, ScreenKeepPolicy.PROXIMITY_GRACE_MS + OFF_CHECK_SLACK_MS)" in look)
        assertTrue("said once per change", "proximityEdge.off(covered, ScreenKeepPolicy.proximityNearText(ProximityLock.near))?.let(::log)" in look)
        val listener = mode.between("val listener = object : DisplayManager.DisplayListener", "displayListener = listener")
        assertTrue(listener, "lookAtTheDisplay()" in listener && "proximityEdge.on()?.let(::log)" in listener)
        assertFalse("the listener no longer ends the mode by itself", "if (change.off) exit(" in listener)
        assertTrue("a sensor change looks again", "proximity.onSensorChange = ::lookAtTheDisplay" in mode)
        // 0.3.99: the receiver also tells the state lines of the screen event (ScreenWatch), then ends the mode as before.
        val receiver = mode.between("override fun onReceive(context: Context, intent: Intent) {", "screenOff = off")
        assertTrue("the broadcast is a real off by its nature", "ScreenWatch.screenEvent(intent.action)" in receiver && "exit(ScreenLock.Exit.SCREENOFF)" in receiver)
    }

    @Test
    fun `the keeper does not light a display the lock turned off, counts no gap and no dark retry, and lights a real off as before`() {
        val ask = keeper.between("private fun ask(", "private fun holdBackForProximity()")
        assertTrue(ask, "ScreenKeepPolicy.why(receiver != null, lastWakeMs?.let { now() - it }, ScreenKeepFront.paused, proximityNow())" in ask)
        assertTrue(ask, "if (why == ScreenKeepPolicy.WHY_PROXIMITY) return holdBackForProximity()" in ask)
        assertTrue("the wake is after it", ask.indexOf("return holdBackForProximity()") < ask.indexOf("wake(reason)"))
        val now = keeper.between("private fun proximityNow()", "private fun ask(")
        assertTrue(now, "ProximityLock.held.value && ProximityLock.covers(power().isInteractive, offSinceMs, now())" in now)
        val safety = keeper.between("private fun armSafety()", "private fun turnOff()")
        assertTrue(safety, "if (retry || proximityNoted || proximityNow()) {" in safety)
        assertTrue("the dark retry comes after, so its count is not touched", safety.indexOf("proximityNow()") < safety.indexOf("darkTries++"))
        val hold = keeper.between("private fun holdBackForProximity()", "private fun wake(")
        assertTrue(hold, "if (!proximityNoted) {" in hold && "log(ScreenKeepPolicy.nothingLine(ScreenKeepPolicy.WHY_PROXIMITY))" in hold)
        assertTrue("one look after the grace when the sensor reads uncovered", "if (ProximityLock.near != false || proximityCheck != null) return" in hold && "main.postDelayed(check, ScreenKeepPolicy.PROXIMITY_GRACE_MS + CHECK_SLACK_MS)" in hold)
        assertTrue("it forgets with the screen on and when it stops", "proximityNoted = false" in keeper.between("private fun screenOn()", "private fun proximityNow()") && "proximityCheck = null" in keeper.between("private fun turnOff()", "private fun screenOff("))
    }

    @Test
    fun `the text of the switch says the pocket, in the three languages`() {
        for ((folder, words) in listOf("values" to "proximity sensor turns the screen off without locking", "values-pt" to "sensor de proximidade apaga a tela sem trancar", "values-es" to "sensor de proximidad apaga la pantalla sin bloquear")) {
            val text = Regex("""<string name="lock_mode_info">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
            assertTrue("$folder: $text", words in text)
        }
    }

    @Test
    fun `no permission and no setting was added`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("WAKE_LOCK was already there", 1, Regex("""android\.permission\.WAKE_LOCK""").findAll(manifest).count())
        assertFalse(source("settings/REauSettings.kt").contains("proximity", ignoreCase = true))
    }
}
