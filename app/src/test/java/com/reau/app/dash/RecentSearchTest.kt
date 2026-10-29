package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The ride log of 20/10/2026 had the scan lookup twice in the same millisecond, and 12 trios of `unavailable` lines in 2m39. */
class RecentSearchTest {
    @Test
    fun `a second search for the same name within a second gets the first answer without running`() {
        var now = 10_000L
        val search = RecentSearch<String?>(minGapMs = 1_000) { now }
        val runs = AtomicInteger()
        assertEquals("RE_1", search.get("RE_") { runs.incrementAndGet(); "RE_1" })
        now += 999
        assertEquals("RE_1", search.get("RE_") { runs.incrementAndGet(); "RE_2" })
        assertEquals(1, runs.get())
        now += 1
        assertEquals("a second later it runs again", "RE_2", search.get("RE_") { runs.incrementAndGet(); "RE_2" })
        assertEquals(2, runs.get())
    }

    @Test
    fun `another name runs, and a no-answer is remembered like any`() {
        val search = RecentSearch<String?>(minGapMs = 1_000) { 0L }
        assertNull(search.get("RE_") { null })
        assertNull(search.get("RE_") { error("must not run") })
        assertEquals("X", search.get("X_") { "X" })
    }

    @Test
    fun `the news of a search that ran comes after the lock is let go, and not for a repeated answer`() {
        val search = RecentSearch<String?>(minGapMs = 1_000) { 0L }
        val told = mutableListOf<String?>()
        var heldDuringAfter = true
        val answer = search.get("RE_", after = {
            told += it
            // another thread can take the lock now: it is not held here
            heldDuringAfter = Thread.holdsLock(search)
        }) { "RE_1" }
        assertEquals("RE_1", answer)
        assertEquals(listOf<String?>("RE_1"), told)
        assertFalse(heldDuringAfter)
        search.get("RE_", after = { told += it }) { error("must not run") }
        assertEquals("a repeated answer tells nothing", 1, told.size)
    }

    @Test
    fun `two callers at once run it once`() {
        val search = RecentSearch<String?>(minGapMs = 60_000)
        val runs = AtomicInteger()
        val go = CountDownLatch(1)
        val threads = List(2) { thread { go.await(); search.get("RE_") { runs.incrementAndGet(); Thread.sleep(50); "RE_1" } } }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, runs.get())
    }

    @Test
    fun `an absence of the dash writes its line the first time and then every fifth`() {
        val said = (1..12).filter { unavailableLogDue(it) }
        assertEquals(listOf(1, 5, 10), said)
        assertTrue(unavailableLogDue(1))
        assertFalse(unavailableLogDue(0))
        assertFalse(unavailableLogDue(2))
    }
}
