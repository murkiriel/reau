package com.reau.app.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The lock every line of the ride log goes through is shared with the video, session and Wi-Fi threads, so no disk work may be done holding it. Where
 * [RideDiagnosticsLockTest] allows three seconds for 300 lines, these tests are strict: with one piece of disk work held on purpose (the rider's cleaning
 * of the folder, the trimming that follows the new file, the opening's own tidying, the days kept), one line logged by another thread must come back in
 * under [LINE_LIMIT_MS]. A line that has to wait for the disk work would not come back before it ends, which is what the log did when the disk
 * work was done under the lock. The rest are the invariants of the same arrangement: no line lost or out of order across the rider's deletion, the file just
 * opened never removed, the notes of the space and of the age said as before, and the operations that change the open file still one at a time.
 */
class RideDiagnosticsLatencyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var diag: File

    @Before
    fun setUp() {
        diag = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(diag, today = { "20261115" })
        OwnLogcat.testOnlyUseDir(diag)
    }

    @After
    fun tearDown() {
        OwnLogcat.testOnlyReset()
        RideDiagnostics.testOnlyReset()
    }

    private fun rides() = diag.listFiles { f -> f.name.startsWith("ride-") }!!.toList()

    /** A thread with a name the hooks can tell it by; a daemon, so one stuck on a lock cannot keep the test run alive. */
    private fun named(name: String, body: () -> Unit): Thread = Thread(Runnable { body() }, name).apply { isDaemon = true; start() }

    /** Enough lines for the clock, the formatting and the queue to be warm, written and flushed. */
    private fun warmUp() {
        repeat(300) { RideDiagnostics.log("warm", "line $it") }
        RideDiagnostics.logAndWait("warm", "end")
    }

    /**
     * [lines] lines logged one by one from a thread of its own; the slowest one in ms, or null if the thread did not finish in [waitMs] (it is
     * stuck waiting for the lock).
     */
    private fun slowestLineMs(tag: String, lines: Int = 30, waitMs: Long = 5_000): Long? {
        var worstNs = 0L
        val logger = named("logger-$tag") {
            repeat(lines) {
                val start = System.nanoTime()
                RideDiagnostics.log(tag, "line $it")
                worstNs = maxOf(worstNs, System.nanoTime() - start)
            }
        }
        logger.join(waitMs)
        return if (logger.isAlive) null else worstNs / 1_000_000
    }

    /**
     * Three rounds of lines (tags `video-1` to `video-3`) while [what] is held; the best round's slowest line must be under [LINE_LIMIT_MS] (the best of three,
     * so a pause of the garbage collector or of a busy machine in one round is not taken for the lock).
     */
    private fun assertLoggingIsNotHeld(what: String) {
        val rounds = (1..3).map { round ->
            slowestLineMs("video-$round") ?: throw AssertionError("a line of the video thread was still waiting 5 s after $what began: the lock is held for the disk work")
        }
        assertTrue("the slowest line of each round while $what was held, in ms: $rounds (limit $LINE_LIMIT_MS)", rounds.minOrNull()!! < LINE_LIMIT_MS)
    }

    @Test
    fun `while the rider's deletion cleans the folder, a line of another thread is written in under 50 ms`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        warmUp()
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val clearing = named("clearing") {
            RideDiagnostics.clearAndReopen { d ->
                inside.countDown()
                finish.await(30, TimeUnit.SECONDS)
                d.listFiles()?.forEach { it.delete() }
                "cleaned"
            }
        }
        try {
            assertTrue("the cleaning began", inside.await(5, TimeUnit.SECONDS))
            assertLoggingIsNotHeld("the cleaning of the folder")
            assertTrue("the cleaning is still held", clearing.isAlive)
        } finally {
            finish.countDown()
        }
        clearing.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")
        val lines = rides().single().readLines()
        assertTrue(lines.toString(), "the rider deleted the logs: cleaned" in lines.first())
        assertEquals("every line logged during the cleaning, once", 90, lines.count { "[video-" in it })
    }

    @Test
    fun `while the trimming after the deletion's new file runs, a line of another thread is written in under 50 ms`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        warmUp()
        val trimming = CountDownLatch(1)
        val finish = CountDownLatch(1)
        RideDiagnostics.testOnlyDiskWork = { what ->
            if (what == "trim" && Thread.currentThread().name == "clearing") {
                trimming.countDown()
                finish.await(30, TimeUnit.SECONDS)
            }
        }
        val clearing = named("clearing") { RideDiagnostics.clearAndReopen { d -> d.listFiles()?.forEach { it.delete() }; "cleaned" } }
        try {
            assertTrue("the trimming began", trimming.await(5, TimeUnit.SECONDS))
            assertLoggingIsNotHeld("the trimming of the new file's folder")
            assertTrue("the trimming is still held", clearing.isAlive)
        } finally {
            finish.countDown()
        }
        clearing.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")
        val lines = rides().single().readLines()
        assertTrue(lines.toString(), "the rider deleted the logs: cleaned" in lines.first())
        assertEquals("every line logged during the trimming, once", 90, lines.count { "[video-" in it })
    }

    @Test
    fun `while the opening's own tidying trims by age, a line of another thread is written in under 50 ms`() {
        val trimming = CountDownLatch(1)
        val finish = CountDownLatch(1)
        RideDiagnostics.testOnlyDiskWork = { what ->
            if (what == "trim" && Thread.currentThread().name == "diag-tidy") {
                trimming.countDown()
                finish.await(30, TimeUnit.SECONDS)
            }
        }
        RideDiagnostics.startProcess("app")
        try {
            assertTrue("the tidying began", trimming.await(5, TimeUnit.SECONDS))
            // The warm-up on a thread of its own: on the test's thread a warm-up held by the lock would just wait for the tidying to end, and the
            // measure after it would then find the lock free (16/11/2026: that let "the age trimmed with the lock" through).
            val warm = named("warm-up") { warmUp() }
            warm.join(5_000)
            assertTrue("the warm-up was not held by the opening's tidying", !warm.isAlive)
            assertLoggingIsNotHeld("the opening's tidying")
        } finally {
            finish.countDown()
        }
        RideDiagnostics.testOnlyAwaitTidy()
        RideDiagnostics.logAndWait("ui", "after")
        val lines = rides().single().readLines()
        assertEquals("every line logged during the tidying, once", 90, lines.count { "[video-" in it })
    }

    @Test
    fun `while the days kept delete the old files, a line of another thread is written in under 50 ms`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        warmUp()
        val old = File(diag, "ride-20260101-080000.log").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 40L * DAY_MS) }
        val trimming = CountDownLatch(1)
        val finish = CountDownLatch(1)
        RideDiagnostics.testOnlyDiskWork = { what ->
            if (what == "trim" && Thread.currentThread().name == "applying") {
                trimming.countDown()
                finish.await(30, TimeUnit.SECONDS)
            }
        }
        val applying = named("applying") { RideDiagnostics.applyKeepDays(30) }
        try {
            assertTrue("the trimming began", trimming.await(5, TimeUnit.SECONDS))
            assertLoggingIsNotHeld("the days kept")
            assertTrue("the trimming is still held", applying.isAlive)
        } finally {
            finish.countDown()
        }
        applying.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")
        assertTrue("the old file went", !old.exists())
        val lines = rides().single().readLines()
        assertEquals("every line logged during the trimming, once", 90, lines.count { "[video-" in it })
        assertTrue(lines.toString(), lines.any { "diag age: 1 file older than 30 days removed" in it })
    }

    @Test
    fun `lines logged without a pause across the rider's deletion come out whole and in order, none doubled`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        val logged = AtomicInteger()
        // Paced (about 50 us a line) so that the lines held for the 100 ms of the cleaning stay far from the log's bound.
        val logger = named("stream") {
            repeat(TOTAL) { n ->
                RideDiagnostics.log("stream", "n=$n")
                logged.set(n + 1)
                LockSupport.parkNanos(50_000)
            }
        }
        val began = System.currentTimeMillis()
        while (logged.get() < 50 && System.currentTimeMillis() - began < 5_000) Thread.sleep(1)
        val said = RideDiagnostics.clearAndReopen { d ->
            Thread.sleep(100)
            d.listFiles()?.forEach { it.delete() }
            "cleaned"
        }
        logger.join(20_000)
        RideDiagnostics.logAndWait("ui", "after")

        assertEquals("cleaned", said)
        val lines = rides().single().readLines()
        assertTrue(lines.take(3).toString(), "the rider deleted the logs: cleaned" in lines.first())
        val numbers = lines.filter { "[stream] n=" in it }.map { it.substringAfter("[stream] n=").toInt() }
        assertTrue("the new file holds the lines of the cleaning", numbers.isNotEmpty())
        // What came before the deletion went with the old file; from the first line the new one holds, nothing is missing, doubled or out of order.
        assertEquals("the lines of the new file, in order from the first to the last", (numbers.first() until TOTAL).toList(), numbers)
        assertTrue("no line was dropped", lines.none { "lines dropped" in it })
        assertTrue("the later line comes last", "[ui] after" in lines.last())
    }

    @Test
    fun `the file the deletion opens is never removed to make room, with the ceiling as low as it goes`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        RideDiagnostics.logAndWait("ui", "before")
        val old = rides().single().name
        File(diag, "crash-20260101-000000.txt").writeBytes(ByteArray(8_192))
        // A file the trimming never removes (a mark of what was reported) keeps the folder over its ceiling, so that the trimming goes on to
        // every log it may remove: if the new file were not out of its reach, it would go too.
        File(diag, "exit-reported").writeBytes(ByteArray(100))
        RideDiagnostics.spaceCapBytes = 1
        // Looked at as the trimming by age begins, right after the trimming by space: the writer has written nothing in the new file yet (it
        // would make it again if it had been removed), so what is on the disk is what the trimming left.
        val seen = AtomicReference<List<String>>(emptyList())
        RideDiagnostics.testOnlyDiskWork = { what -> if (what == "trim") seen.set(rides().map { it.name }) }
        RideDiagnostics.clearAndReopen { "kept" }
        RideDiagnostics.logAndWait("ui", "after")

        assertTrue("the new file was still there when the trimming ended: ${seen.get()} (the old one was $old)", seen.get().any { it != old })
        val fresh = rides().single { "the rider deleted the logs" in it.readText() }
        assertTrue(fresh.readText(), "[ui] after" in fresh.readText())
    }

    @Test
    fun `the deletion's new file says what the space and the age took, one line each, after its header`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.testOnlyAwaitTidy()
        File(diag, "crash-20260101-000000.txt").apply { writeBytes(ByteArray(8_192)); setLastModified(System.currentTimeMillis() - 60L * DAY_MS) }
        val video = File(tmp.root, "video").apply { mkdirs() }
        File(video, "video-1.h264").apply { writeBytes(ByteArray(10)); setLastModified(System.currentTimeMillis() - 40L * DAY_MS) }
        RideDiagnostics.spaceCapBytes = 2_000
        RideDiagnostics.keepDays = 30
        RideDiagnostics.clearAndReopen { "kept" }
        RideDiagnostics.logAndWait("ui", "after")

        val lines = rides().map { it.readLines() }.single { l -> l.any { "the rider deleted the logs" in it } }
        val header = lines.indexOfFirst { "the rider deleted the logs: kept" in it }
        val space = lines.indexOfFirst { "diag space: 1 file removed" in it }
        val age = lines.indexOfFirst { "diag age: 1 file older than 30 days removed" in it }
        assertEquals(lines.toString(), 0, header)
        assertTrue("the space is said right after the header: $lines", space == header + 1)
        assertTrue("the age is said after the space: $lines", age == space + 1)
        assertTrue("the video older than the days kept went", !File(video, "video-1.h264").exists())
    }

    @Test
    fun `while the deletion goes on, the projection's stop waits for it and its line is written in the new file`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.start("projection")
        RideDiagnostics.logAndWait("ui", "before")
        val inside = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val clearing = named("clearing") {
            RideDiagnostics.clearAndReopen { d ->
                inside.countDown()
                finish.await(30, TimeUnit.SECONDS)
                d.listFiles()?.forEach { it.delete() }
                "cleaned"
            }
        }
        var waited = false
        var stopping: Thread? = null
        try {
            assertTrue("the cleaning began", inside.await(5, TimeUnit.SECONDS))
            // The stop is one of the operations that change which file is open: it must wait for the deletion, not run in the middle of it
            // (with no file open its line would be lost).
            val stop = named("stopping") { RideDiagnostics.stop("projection") }
            stopping = stop
            stop.join(300)
            waited = stop.isAlive
        } finally {
            finish.countDown()
        }
        clearing.join(10_000)
        stopping?.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")
        assertTrue("the stop waited for the deletion", waited)
        val text = rides().single().readText()
        assertTrue(text, "---- projeção desligada: projection ----" in text)
        assertTrue("the stop is not before the header of the new file: $text", text.indexOf("the rider deleted the logs") < text.indexOf("projeção desligada"))
    }

    private companion object {
        const val LINE_LIMIT_MS = 50L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TOTAL = 3_000
    }
}
