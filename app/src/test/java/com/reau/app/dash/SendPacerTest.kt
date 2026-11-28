package com.reau.app.dash

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer that keeps 2 ms between any two packets to the dash (plan 35 S, the owner's decision of 25/11/2026), with a clock and a wait of the tests' own: no
 * real time passes. The fake wait moves the clock to the deadline, as a real wait does.
 */
class SendPacerTest {
    private val ms = 1_000_000L
    private val clock = AtomicLong(0L)
    private val deadlines = ConcurrentLinkedQueue<Long>()

    private fun pacer(enabled: Boolean = true) = SendPacer(enabled, nanoTime = { clock.get() }, waitUntil = { deadline ->
        deadlines.add(deadline)
        clock.accumulateAndGet(deadline) { a, b -> maxOf(a, b) }
    })

    @Test
    fun `the gap is 2 ms`() {
        assertEquals(2_000_000L, SendPacer.GAP_NS)
    }

    @Test
    fun `the first packet leaves at once`() {
        val p = pacer()
        clock.set(5 * ms)
        assertEquals(0L, p.pace())
        assertTrue(deadlines.isEmpty())
    }

    @Test
    fun `two sends one after the other wait what is missing up to 2 ms`() {
        val p = pacer()
        assertEquals(0L, p.pace())
        assertEquals("nothing has passed: the whole gap", 2 * ms, p.pace())
        assertEquals(listOf(2 * ms), deadlines.toList())
        assertEquals("the wait moved the clock to the slot", 2 * ms, clock.get())
    }

    @Test
    fun `a second send 0 point 5 ms after the first waits only the 1 point 5 ms that are missing`() {
        val p = pacer()
        p.pace()
        clock.set(ms / 2)
        assertEquals(3 * ms / 2, p.pace())
        assertEquals(listOf(2 * ms), deadlines.toList())
    }

    @Test
    fun `a send 3 ms after the previous one does not wait`() {
        val p = pacer()
        p.pace()
        clock.set(3 * ms)
        assertEquals(0L, p.pace())
        assertTrue(deadlines.isEmpty())
    }

    @Test
    fun `a send exactly 2 ms after the previous one does not wait`() {
        val p = pacer()
        p.pace()
        clock.set(2 * ms)
        assertEquals(0L, p.pace())
    }

    @Test
    fun `a keyframe of 17 packets leaves 2 ms apart and takes 32 ms from the first to the last`() {
        val p = pacer()
        val sentAt = List(17) {
            p.pace()
            clock.get()
        }
        assertEquals(sentAt.indices.map { it * 2 * ms }, sentAt)
        assertEquals(32 * ms, sentAt.last() - sentAt.first())
    }

    @Test
    fun `a command between two video packets takes the next slot, not the one after the whole keyframe`() {
        val p = pacer()
        p.pace() // video 1 at 0
        val command = p.reserve(clock.get()) // a command asks at the same instant
        val video2 = p.reserve(clock.get())
        assertEquals(2 * ms, command)
        assertEquals(4 * ms, video2)
    }

    @Test
    fun `with the switch off nothing waits and the line says off`() {
        val p = pacer(enabled = false)
        repeat(10) { assertEquals(0L, p.pace()) }
        assertTrue(deadlines.isEmpty())
        assertEquals(0L, clock.get())
        assertEquals("pace=off", p.windowText())
        assertFalse(p.enabled)
    }

    @Test
    fun `a window says what was waited in all and the longest wait of one packet, and starts again`() {
        val p = pacer()
        p.pace()
        p.pace() // 2 ms
        clock.addAndGet(ms / 2)
        p.pace() // 1.5 ms
        assertEquals("pace=on waited=3.5ms max=2.0ms", p.windowText())
        assertEquals("the next window starts from zero", "pace=on waited=0.0ms max=0.0ms", p.windowText())
        assertEquals("the total goes on", 3L, p.totalWaitedMs())
    }

    @Test
    fun `from four threads at the same instant no two slots are less than 2 ms apart`() {
        // The clock gives a different reading to each caller, 1 us apart, so the slots are really contended.
        val ticking = AtomicLong(0L)
        val p = SendPacer(nanoTime = { ticking.addAndGet(1_000L) }, waitUntil = {})
        val slots = ConcurrentLinkedQueue<Long>()
        val go = CountDownLatch(1)
        val threads = List(4) {
            Thread {
                go.await()
                repeat(250) { slots.add(p.reserve(ticking.addAndGet(1_000L))) }
            }.also { it.start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        val sorted = slots.sorted()
        assertEquals(1000, sorted.size)
        assertEquals("every slot is its own", 1000, sorted.toSet().size)
        val closest = sorted.zipWithNext { a, b -> b - a }.min()
        assertTrue("two packets $closest ns apart", closest >= SendPacer.GAP_NS)
    }

    @Test
    fun `from four threads at one frozen instant the waits are the slots 0, 2, 4 ms and so on whatever the order`() {
        val p = SendPacer(nanoTime = { 0L }, waitUntil = {})
        val waited = ConcurrentLinkedQueue<Long>()
        val go = CountDownLatch(1)
        val threads = List(4) {
            Thread {
                go.await()
                repeat(50) { waited.add(p.pace()) }
            }.also { it.start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals((0 until 200).map { it * 2 * ms }, waited.sorted())
    }

    @Test
    fun `a real wait lasts until its deadline and an interrupted thread does not wait and keeps its flag`() {
        val deadline = System.nanoTime() + 3 * ms
        SendPacer.parkUntil(deadline)
        assertTrue("waited to the deadline", System.nanoTime() >= deadline)
        Thread.currentThread().interrupt()
        try {
            val far = System.nanoTime() + 10_000 * ms
            SendPacer.parkUntil(far)
            assertTrue("did not wait for it", System.nanoTime() < far)
            assertTrue("the flag is still set", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
