package com.reau.app.dash.video

import android.media.MediaCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit of 20/10/2026: the web encoder was released 200 ms after its output thread was told to stop, though that thread could be
 * inside a send to a slow viewer, still holding the codec's buffer. [EncoderOutputPump.stopThen] lets the codec go only when the
 * thread is out.
 */
class EncoderOutputPumpTest {
    /** A codec that hands out one buffer at a time (index 0) when asked, and nothing otherwise. */
    private class Fake(val buffers: AtomicInteger = AtomicInteger(0)) {
        val dequeue: (MediaCodec.BufferInfo) -> Int = {
            if (buffers.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) 0 else { Thread.sleep(5); -1 }
        }
    }

    @Test
    fun `an idle pump is out at once and the release runs on the caller's thread`() {
        val pump = EncoderOutputPump(Fake().dequeue) { _, _ -> }
        pump.start()
        val ranOn = AtomicReference<Thread>()
        pump.stopThen { ranOn.set(Thread.currentThread()) }
        assertEquals(Thread.currentThread(), ranOn.get())
    }

    @Test
    fun `a pump kept inside a send releases the codec from its own thread, once it is out of it`() {
        val fake = Fake()
        val inSend = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val released = CountDownLatch(1)
        val ranOn = AtomicReference<Thread>()
        val runs = AtomicInteger()
        val pump = EncoderOutputPump(fake.dequeue) { _, _ ->
            inSend.countDown()
            letGo.await(10, TimeUnit.SECONDS) // a slow viewer holds the send
        }
        pump.start()
        fake.buffers.set(1)
        assertTrue("the send never began", inSend.await(5, TimeUnit.SECONDS))

        pump.stopThen {
            runs.incrementAndGet()
            ranOn.set(Thread.currentThread())
            released.countDown()
        }
        // stopThen gave up waiting (the send is still going) and the codec has not been released under it.
        assertEquals("released under a send that is still going", 0, runs.get())

        letGo.countDown()
        assertTrue("never released", released.await(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
        assertNotEquals("from the pump's own thread", Thread.currentThread(), ranOn.get())
        assertEquals(EncoderOutputPump.THREAD_NAME, ranOn.get().name)
    }

    @Test
    fun `the release runs exactly once however the stop and the exit meet`() {
        repeat(200) {
            val pump = EncoderOutputPump(Fake().dequeue) { _, _ -> }
            pump.start()
            val runs = AtomicInteger()
            pump.stopThen { runs.incrementAndGet() }
            Thread.sleep(1)
            assertEquals(1, runs.get())
        }
    }

    @Test
    fun `an encoder without a pump lets the codec go at once`() {
        val runs = AtomicInteger()
        EncoderOutputPump.releaseThrough(null) { runs.incrementAndGet() }
        assertEquals(1, runs.get())
    }

    @Test
    fun `both encoders release through the pump, so the codec never goes under a send that is still going`() {
        val fake = Fake()
        val inSend = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val released = CountDownLatch(1)
        val runs = AtomicInteger()
        val pump = EncoderOutputPump(fake.dequeue) { _, _ ->
            inSend.countDown()
            letGo.await(10, TimeUnit.SECONDS)
        }
        pump.start()
        fake.buffers.set(1)
        assertTrue("the send never began", inSend.await(5, TimeUnit.SECONDS))

        EncoderOutputPump.releaseThrough(pump) {
            runs.incrementAndGet()
            released.countDown()
        }
        assertEquals("released under a send that is still going", 0, runs.get())

        letGo.countDown()
        assertTrue("never released", released.await(5, TimeUnit.SECONDS))
        assertEquals(1, runs.get())
    }
}
