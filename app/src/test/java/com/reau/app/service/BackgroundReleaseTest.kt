package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A release that takes seconds must not hold the caller (the main thread's onDestroy): see [BackgroundRelease]. */
class BackgroundReleaseTest {
    @Test
    fun `the caller gets control back at once, however long the release takes`() {
        val release = CountDownLatch(1)
        val ran = CountDownLatch(1)
        val started = System.nanoTime()
        val worker = BackgroundRelease.run("test-release", { }) {
            release.await(5, TimeUnit.SECONDS)
            ran.countDown()
        }
        val returnedInMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $returnedInMs ms", returnedInMs < 1_000)
        assertEquals("the release is still going", 1L, ran.count)
        assertTrue(worker.isDaemon)
        assertEquals("test-release", worker.name)
        release.countDown()
        assertTrue("the release ran to the end", ran.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a release that throws is reported and costs the caller nothing`() {
        val failure = AtomicReference<Throwable>()
        val reported = CountDownLatch(1)
        val worker = BackgroundRelease.run("test-release", { failure.set(it); reported.countDown() }) {
            throw IllegalStateException("compositor already gone")
        }
        assertTrue(reported.await(2, TimeUnit.SECONDS))
        assertEquals("compositor already gone", failure.get().message)
        worker.join(2_000)
        assertFalse(worker.isAlive)
    }
}
