package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A read of a dead Android Auto does not answer for 5 s; REau's screen froze for that long, on the main thread (19 and 20/10/2026). */
class ReadWatchTest {
    @Test
    fun `a read that answers in time is finished and nothing is overdue`() {
        val watch = ReadWatch(limitMs = 3_000)
        assertFalse(watch.inFlight())
        val id = watch.start(1_000)
        watch.begin(id, 1_000)
        assertTrue(watch.inFlight())
        assertFalse(watch.overdue(3_999))
        assertTrue(watch.finish(id))
        assertFalse(watch.inFlight())
        assertFalse(watch.overdue(60_000))
    }

    @Test
    fun `a read without an answer past the limit is overdue, exactly after it`() {
        val watch = ReadWatch(limitMs = 3_000)
        val id = watch.start(1_000)
        watch.begin(id, 1_000)
        assertFalse(watch.overdue(4_000))
        assertTrue(watch.overdue(4_001))
    }

    @Test
    fun `a read that waits behind the worker's other work is counted from when the worker takes it up`() {
        val watch = ReadWatch(limitMs = 3_000, queueLimitMs = 15_000)
        val id = watch.start(0)
        assertFalse("waiting in the queue is not Android Auto being gone", watch.overdue(10_000))
        watch.begin(id, 10_000)
        assertFalse(watch.overdue(13_000))
        assertTrue(watch.overdue(13_001))
    }

    @Test
    fun `a read never taken up by the worker is overdue after the queue limit, exactly after it`() {
        val watch = ReadWatch(limitMs = 3_000, queueLimitMs = 15_000)
        watch.start(1_000)
        assertFalse(watch.overdue(16_000))
        assertTrue(watch.overdue(16_001))
    }

    @Test
    fun `the take-up of a read given up on does not start the clock of the next`() {
        val watch = ReadWatch(limitMs = 3_000, queueLimitMs = 15_000)
        val first = watch.start(0)
        watch.reset()
        watch.start(20_000)
        watch.begin(first, 20_500)
        assertFalse(watch.overdue(24_000)) // still in the queue: only the queue limit (15 s) applies
    }

    @Test
    fun `the late answer of a read given up on is not the answer of the next one`() {
        val watch = ReadWatch(limitMs = 3_000)
        val first = watch.start(0)
        watch.reset() // the walk was ended as gone
        val second = watch.start(10_000)
        assertFalse("the first one answers at last", watch.finish(first))
        assertTrue("the second is still under way", watch.inFlight())
        assertTrue(watch.finish(second))
    }

    @Test
    fun `a reset forgets what was under way`() {
        val watch = ReadWatch()
        val id = watch.start(0)
        watch.reset()
        assertFalse(watch.inFlight())
        assertFalse(watch.finish(id))
        assertEquals(3_000L, ReadWatch.LIMIT_MS)
    }
}
