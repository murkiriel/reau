package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main thread's watch driven by hand ([MainThreadWatch.tick]): the pings, the lines of one stall, and that nothing it does can throw. */
class MainThreadWatchTest {
    private var now = 5_000L
    private val posted = mutableListOf<Runnable>()
    private val lines = mutableListOf<String>()
    private var stack: () -> List<StackTraceElement> = { listOf(StackTraceElement("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", 1)) }
    private var write: (String) -> Unit = { lines += it }
    private val watch = MainThreadWatch({ now }, { posted += it }, { stack() }, { write(it) })

    private fun tickAt(ms: Long) {
        now = ms
        watch.tick()
    }

    /** The main thread runs what was posted, at [ms]. */
    private fun mainRunsAt(ms: Long) {
        now = ms
        posted.forEach { it.run() }
        posted.clear()
    }

    @Test
    fun `a healthy main thread is pinged once a second and nothing is written`() {
        var pings = 0
        for (t in 5_000L..14_500L step 500L) {
            tickAt(t)
            pings += posted.size
            mainRunsAt(t + 5)
        }
        assertEquals(10, pings)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `one stall is told at one second three and five and ends with its whole time`() {
        val top = "android.os.MessageQueue.nativePollOnce"
        tickAt(5_000)
        assertEquals(1, posted.size)
        tickAt(5_500)
        assertEquals(emptyList<String>(), lines)
        tickAt(6_000)
        assertEquals("main thread not answering for 1.0 s, at: $top", lines.single())
        tickAt(6_500)
        assertEquals(1, lines.size)
        tickAt(8_000)
        assertEquals("main thread not answering for 3.0 s, at: $top", lines[1])
        tickAt(10_000)
        assertEquals("main thread not answering for 5.0 s, at: $top", lines[2])
        tickAt(14_000)
        assertEquals(3, lines.size)
        assertEquals("only the ping now waiting, no new one", 1, posted.size)
        mainRunsAt(14_300)
        tickAt(14_500)
        assertEquals("main thread answered again after 9.3 s", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun `after the main thread is back a new ping goes out and a new stall is told again`() {
        tickAt(5_000)
        mainRunsAt(5_000 + 3_000)
        tickAt(8_500)
        assertEquals("main thread answered again after 3.0 s", lines.single())
        assertEquals("the next ping goes out in the same look", 1, posted.size)
        tickAt(9_500)
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].startsWith("main thread not answering for 1.0 s"))
    }

    @Test
    fun `the main thread is never waited for, a stall only leaves a ping waiting`() {
        tickAt(5_000)
        tickAt(5_500)
        tickAt(6_000)
        assertEquals(1, posted.size)
    }

    @Test
    fun `a log that throws or a stack that cannot be read never stops a look`() {
        write = { error("disk full") }
        tickAt(5_000)
        tickAt(6_000)
        assertFalse(posted.isEmpty())
        write = { lines += it }
        stack = { error("no stack") }
        tickAt(7_000)
        tickAt(8_000)
        assertTrue(lines.toString(), lines.single().endsWith(", at: no stack"))
    }

    @Test
    fun `the thread is a daemon, a second start does nothing, and it ends at the stop`() {
        val real = MainThreadWatch(com.reau.app.util.MonotonicClock::nowMs, { it.run() }, { emptyList() }, { })
        fun running() = Thread.getAllStackTraces().keys.filter { it.name == "reau-main-watch" && it.isAlive }
        real.start()
        real.start()
        try {
            assertEquals(1, running().size)
            assertTrue(running().single().isDaemon)
        } finally {
            real.stop()
        }
        val until = System.nanoTime() + 3_000_000_000L
        while (running().isNotEmpty() && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(emptyList<Thread>(), running())
        real.stop()
    }
}
