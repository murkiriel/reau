package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the idle lock reaches the window: the keep-on flag put and taken off by this mechanism, the reading of the system's screen timeout, the restart on a touch and the entry into
 * REau's lock. Of the text of the source (the places need an Activity and a window, as in the other "...IsWired" tests); the rules are in IdleLockTest.
 */
class IdleLockIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val watch = source("ui/IdleLockWatch.kt")

    @Test
    fun `the flag is put and taken off by a source of its own, and the window's flag is shared with the lock by a counter`() {
        val start = watch.substringAfter("private fun start()").substringBefore("private fun stop(")
        assertTrue(start, "keep.want(SOURCE)" in start && "IdleLock.onLine(timeout)" in start)
        val stop = watch.substringAfter("private fun stop(").substringBefore("/** The phone's own screen timeout")
        assertTrue(stop, "keep.release(SOURCE)" in stop && "IdleLock.offLine(reason)" in stop && "main.removeCallbacks(it)" in stop)
        assertEquals("idle source", "\"idle\"", Regex("""SOURCE = ("[a-z]+")""").find(watch)!!.groupValues[1])
        val keep = source("ui/KeepScreenOn.kt")
        assertTrue(keep, "if (sources.want(source)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in keep)
        assertTrue(keep, "if (sources.release(source)) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)" in keep)
        assertFalse("the idle watch never touches the window's flag itself", "FLAG_KEEP_SCREEN_ON" in watch)
        val lock = source("ui/ScreenLockMode.kt")
        assertTrue("the lock uses the same counter, with its own source", "keep.want(SOURCE)" in lock && "keep.release(SOURCE)" in lock && "SOURCE = \"lock\"" in lock)
        assertFalse("the lock does not touch the flag itself either", "FLAG_KEEP_SCREEN_ON" in lock.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n"))
    }

    @Test
    fun `the count is the system's screen timeout, read inside a runCatching, and a touch of the rider begins it again`() {
        assertTrue(watch, "Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, IdleLock.DEFAULT_SYSTEM_MS)" in watch)
        // 0.3.99: a failed read of the timeout is a warning in the log (the read is kept in a value, then given the default).
        assertTrue(watch, "val read = runCatching { systemTimeout() }" in watch && "IdleLock.timeoutMs(read.getOrDefault(IdleLock.DEFAULT_SYSTEM_MS))" in watch)
        assertTrue(watch, "LockLog.warnLine(\"reading the screen timeout\", it)" in watch)
        val touched = watch.substringAfter("fun touched()").substringBefore("private fun evaluate()")
        assertTrue(touched, "running.touch(SystemClock.elapsedRealtime())" in touched && "schedule()" in touched)
        val main = source("MainActivity.kt")
        assertTrue("onUserInteraction restarts it", "idleWatch.touched()" in main.substringAfter("override fun onUserInteraction()"))
    }

    @Test
    fun `when it runs out REau's lock takes the screen by the idle entry, and the conditions come from the button's own`() {
        val fire = watch.substringAfter("private fun fire()")
        assertTrue(fire, "if (running.expired(SystemClock.elapsedRealtime())) mode.enter(ScreenLock.Entry.IDLE) else schedule()" in fire)
        val evaluate = watch.substringAfter("private fun evaluate()").substringBefore("private fun start()")
        assertTrue(evaluate, "IdleLock.offReason(switchOn, p.phase, p.dash, resumed, mode.active)" in evaluate)
        assertTrue("the lock mode tells it of every entry and exit", source("ui/ScreenLockMode.kt").count { it == '\n' } > 0 && "onChange?.invoke()" in source("ui/ScreenLockMode.kt"))
        assertEquals("told twice: in and out", 2, Regex("""onChange\?\.invoke\(\)""").findAll(source("ui/ScreenLockMode.kt")).count())
        assertTrue("mode.onChange = ::evaluate" in watch)
        assertTrue("the phase and the dash are followed", "ProjectionService.projection.map { it.phase to it.dash }.distinctUntilChanged().collect { evaluate() }" in watch)
    }

    @Test
    fun `the activity feeds it with the front, the end of the front and the switch, and the log lines are under the lock tag`() {
        val main = source("MainActivity.kt")
        assertTrue("idleWatch.resumed(true)" in main.substringAfter("private fun resume()").substringBefore("resumedAtMs"))
        assertTrue("idleWatch.resumed(false)" in main.substringAfter("override fun onPause()").substringBefore("super.onPause()"))
        assertTrue("idleWatch.setSwitch(after.lockMode)" in main && "idleWatch.attach()" in main.substringAfter("override fun onPostCreate"))
        assertTrue("RideDiagnostics.log(ScreenLock.TAG, IdleLock.onLine(timeout))" in watch && "RideDiagnostics.log(ScreenLock.TAG, IdleLock.offLine(reason))" in watch)
        assertFalse("no thread was added", "Thread(" in watch || "Executors" in watch)
    }

    @Test
    fun `the text of the switch says the screen goes into REau's lock instead of turning off`() {
        for ((folder, words) in listOf("values" to "goes into REau", "values-pt" to "entra no bloqueio do REau", "values-es" to "entra en el bloqueo de REau")) {
            val text = Regex("""<string name="lock_mode_info">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]
            assertTrue("$folder: $text", words in text)
        }
    }
}
