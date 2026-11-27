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
        assertTrue("it is registered after the lock is taken", hold.indexOf("ProximityLock.hold(") < hold.indexOf("val listening = listen()"))
        assertTrue("unregistered with the lock", "unregisterListener(reader)" in hold.between("private fun stopListening()", "private companion"))
        assertEquals("the keeper reads no sensor of its own", 0, Regex("""SensorManager|Sensor\.""").findAll(keeper).count())
    }

    @Test
    fun `a sensor that cannot be heard lets the lock go at once, so a real off is never taken for the lock for ever`() {
        val listen = hold.between("private fun listen(): Boolean {", "private fun register(")
        assertTrue(listen, "sensors == null -> false.also { log(LockLog.unavailableLine(\"sensor manager\")) }" in listen)
        assertTrue(listen, "sensor == null -> false.also { log(LockLog.unavailableLine(\"proximity sensor\")) }" in listen)
        val register = hold.between("private fun register(", "private fun stopListening()")
        assertTrue(register, "if (!sensors.registerListener(reader, sensor, SensorManager.SENSOR_DELAY_NORMAL, main)) {\n            log(LockLog.unavailableLine(\"proximity sensor listener\"))\n            return false" in register)
        assertTrue("a registered listener is true", "listener = reader\n        log(ScreenLockLog.sensorRegisteredLine(sensor.maximumRange))\n        return true" in register)
        val acquire = hold.between("fun acquire(reason: String)", "fun release(")
        assertTrue(acquire, "val listening = listen()" in acquire && "if (!listening) release(ScreenLock.PROXIMITY_NO_SENSOR)" in acquire)
        assertTrue("it is let go after the lock is taken and said", acquire.indexOf("ProximityLock.hold(") < acquire.indexOf("if (!listening) release(") && acquire.indexOf("log(ScreenLock.proximityLine(true, reason))") < acquire.indexOf("if (!listening) release("))
        assertEquals("no-sensor", ScreenLock.PROXIMITY_NO_SENSOR)
    }

    @Test
    fun `the sensor's lines are grouped, and only the sensor's and the keeper's display lines are`() {
        val listen = hold.between("private fun register(", "private fun stopListening()")
        assertTrue(listen, "val lines = ScreenLockLog.Grouper()" in listen && "lines.hit(SystemClock.elapsedRealtime())?.let { log(ScreenLockLog.sensorLine(near, value, it)) }" in listen)
        assertTrue("the mode is told of each change, said or not", listen.indexOf("lines.hit(") < listen.indexOf("onSensorChange?.invoke()"))
        val seen = keeper.between("private fun seen(", "private fun armSafety()")
        assertTrue(seen, "val change = edge.heard(off, via, now()) ?: return" in seen && "change.line?.let(log)" in seen)
        assertTrue("it acts on the change, said or not", seen.indexOf("change.line?.let(log)") < seen.indexOf("if (off) screenOff() else screenOn()"))
        val trace = code("service/ScreenTrace.kt")
        assertFalse("the lock screen's lines are never grouped: each one matters", "Grouper" in trace)
    }

    @Test
    fun `the system's light and dark switch does not rebuild the activity, which would end the lock mode, and the theme never reads the system`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = manifest.between("android:name=\".MainActivity\"", "android:exported")
        val changes = Regex("""android:configChanges="([^"]*)"""").find(activity)!!.groupValues[1].split("|")
        assertTrue(changes.toString(), "uiMode" in changes)
        for (kept in listOf("locale", "layoutDirection", "orientation", "screenSize", "smallestScreenSize", "screenLayout")) assertTrue(kept, kept in changes)
        // Why nothing is to be redone on the change: the theme is the rider's, drawn from [LocalREauNight], and every REauTheme call passes it.
        val calls = listOf("ui/MainScreen.kt", "ui/ScreenLockScreen.kt", "ui/LockOverHost.kt").map { code(it) }
        for (call in calls) assertTrue("REauTheme(dark = " in call)
        assertEquals("only the unused default reads the system's theme", 1, Regex("""isSystemInDarkTheme\(""").findAll(allSources()).count())
        assertFalse("no night resources to switch", File("src/main/res").listFiles()!!.any { it.name.contains("night") })
        assertFalse("nothing reads the mode of the configuration", Regex("""UI_MODE|uiMode|isNightModeActive""").containsMatchIn(allSources()))
    }

    private fun allSources(): String = File("src/main/java/com/reau/app").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { f ->
        f.readLines().filterNot { Regex("""^\s*(\*|//|/\*)""").containsMatchIn(it) }.joinToString("\n")
    }

    @Test
    fun `the end of the keeper and of the lock mode run every step, each guarded alone with a warning`() {
        val turnOff = keeper.between("private fun turnOff()", "private fun screenOff(")
        assertTrue(turnOff, "runCatching { displayListener?.let { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) } }" in turnOff)
        assertTrue(turnOff, "LockLog.warnLine(\"stop listening to the display\", it)" in turnOff)
        assertTrue("the rest of the way out is after it", turnOff.indexOf("stop listening to the display") < turnOff.indexOf("lock?.takeIf") && turnOff.indexOf("lock?.takeIf") < turnOff.indexOf("ScreenWatch.end(ScreenWatch.SOURCE_KEEPER)"))
        val exit = mode.between("fun exit(reason: ScreenLock.Exit)", "fun dim(on: Boolean")
        val steps = listOf(
            "runCatching { keep.release(SOURCE) }" to "keep screen on release",
            "runCatching { bars(hidden = false) }" to "system bars show",
            "runCatching { back?.remove() }" to "back callback remove",
            "runCatching { displayListener?.let {" to "stop listening to the display",
            "runCatching { screenOff?.let { activity.unregisterReceiver(it) } }" to "stop listening to the screen going off",
            "runCatching { if (!overTheLockScreen()) dim(false) }" to "brightness back",
            "runCatching { ScreenWatch.end(ScreenWatch.SOURCE_LOCK) }" to "state lines stop",
        )
        var at = -1
        for ((step, warning) in steps) {
            val place = exit.indexOf(step)
            assertTrue("guarded: $step", place > at)
            at = place
            assertTrue("with its warning: $warning", "LockLog.warnLine(\"$warning\", it)" in exit.substring(place))
        }
        assertTrue("the summary is guarded too", "runCatching {\n            val at = SystemClock.elapsedRealtime()" in exit && "LockLog.warnLine(\"summary\", it)" in exit)
        assertEquals("nothing outside a runCatching undoes the registrations", 0, Regex("""(?m)^        (displayListener\?\.let|screenOff\?\.let|back\?\.remove|keep\.release|bars\()""").findAll(exit).count())
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
        assertTrue("the sensor's late event is waited for", "} else if (ProximityLock.near != true) {\n            main.postDelayed(offCheck, ScreenKeepPolicy.PROXIMITY_GRACE_MS + OFF_CHECK_SLACK_MS)" in look)
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
        assertTrue("one look after the grace when the sensor reads uncovered", "if (ProximityLock.near == true || proximityCheck != null) return" in hold && "main.postDelayed(check, ScreenKeepPolicy.PROXIMITY_GRACE_MS + CHECK_SLACK_MS)" in hold)
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
