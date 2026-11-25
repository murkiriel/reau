package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The main thread's watch ([MainThreadWatchTest] drives what it says; [com.reau.app.util.MainStallLogTest] decides the lines). Where it is started needs
 * Android and the live sides, so those lines are read from the source, as the other "...IsWired" tests do: the projection starts and stops it, every look
 * and every line is inside a `runCatching`, the thread is a daemon that never waits on the main thread, and the watch reads nothing but the main thread's
 * stack names.
 */
class MainThreadWatchIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val watch = File(main, "service/MainThreadWatch.kt").readText()
    private val stall = File(main, "util/MainStallLog.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    /** The source without comments, so that a word in a KDoc is not read as code. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")

    @Test
    fun `the projection starts the watch in the real app only, and gives it back at the release, in two lines of the live sides`() {
        val start = live.between("scope.launch { watchWaiting() }", "scope.launch { ProjectionMachine.dashLinks")
        assertTrue(start, "if (setup.settings != null) MainThreadWatch.begin()" in start)
        assertTrue(live, "\"main thread watch\" to { MainThreadWatch.end() }" in live)
        assertEquals("the live sides only start and stop it", 2, Regex("MainThreadWatch").findAll(live).count())
    }

    @Test
    fun `starting and stopping are inside a runCatching, so a failure never reaches the projection`() {
        val body = code(watch)
        assertTrue(body, "fun begin() {\n            runCatching { shared.start() }" in body)
        assertTrue(body, "fun end() {\n            runCatching { shared.stop() }" in body)
        assertTrue(body, "}.onFailure { thread = null }" in body)
    }

    @Test
    fun `every look and every line is inside a runCatching`() {
        val body = code(watch)
        assertTrue(body, "runCatching { tick() }" in body)
        assertTrue(body, "if (line != null) runCatching { write(line) }" in body)
        assertTrue(body, "runCatching { mainStack() }" in body)
        val writers = Regex("RideDiagnostics\\.log\\(").findAll(body).count()
        assertEquals("the only place that writes to the ride log is the one given to the constructor", 1, writers)
        assertTrue(body, "write = { RideDiagnostics.log(TAG, it) }" in body)
    }

    @Test
    fun `the thread is a daemon of its own and the main thread only ever sets one number`() {
        val body = code(watch)
        assertTrue(body, "isDaemon = true" in body)
        assertTrue(body, "postToMain(Runnable { answeredAt = clock() })" in body)
        assertTrue(body, "thread?.interrupt()" in body)
        for (never in listOf("runBlocking", ".join(", ".wait(", "Dispatchers.Main", "runOnUiThread", "synchronized(")) {
            assertFalse("$never could make the watch wait or the main thread wait", never in body)
        }
    }

    @Test
    fun `it posts one ping a second and costs a clock reading a look, with nothing on the video's path`() {
        val body = code(watch)
        assertTrue(body, "const val PING_PERIOD_MS = 1_000L" in body)
        assertEquals("one post in the whole class", 1, Regex("postToMain\\(").findAll(body).count())
        for (never in listOf("Choreographer", "ActivityManager", "video", "Video", "MediaCodec")) {
            assertFalse("$never has no place in the watch", never in body)
        }
    }

    @Test
    fun `what is read of the main thread is the names of the top eight frames and nothing else`() {
        val body = code(stall)
        assertTrue(body, "const val STACK_FRAMES = 8" in body)
        assertTrue(body, "frames.take(STACK_FRAMES).joinToString(\" < \") { \"\${it.className}.\${it.methodName}\" }" in body)
        for (never in listOf("fileName", "lineNumber", "toString()", "message")) {
            assertFalse("$never says more than names", never in body)
        }
        assertFalse("the log's rules have no Android in them", "import android" in stall)
    }
}
