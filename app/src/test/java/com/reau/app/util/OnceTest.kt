package com.reau.app.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The dash's pairing (and the Keystore cipher behind it) was made again at every `open`, on the main thread. */
class OnceTest {
    @Test
    fun `the value is made once and then kept`() {
        val once = Once<Any>()
        val made = AtomicInteger()
        val first = once.get { made.incrementAndGet(); Any() }
        val second = once.get { made.incrementAndGet(); Any() }
        assertSame(first, second)
        assertEquals(1, made.get())
    }

    @Test
    fun `asked from many threads at once it is still made once`() {
        val once = Once<Any>()
        val made = AtomicInteger()
        val go = CountDownLatch(1)
        val got = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val threads = List(8) {
            Thread {
                go.await()
                got += once.get { made.incrementAndGet(); Thread.sleep(20); Any() }
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, made.get())
        assertEquals(1, got.toSet().size)
    }

    @Test
    fun `forget makes the next ask make it again`() {
        val once = Once<Any>()
        val first = once.get { Any() }
        once.forget()
        assertEquals(false, first === once.get { Any() })
    }
}
