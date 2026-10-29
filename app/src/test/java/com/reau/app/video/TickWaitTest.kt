package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wait of a new picture for the compositor's tick, measured for the `video fps` line. */
class TickWaitTest {
    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun `a picture waits from its arrival to its draw`() {
        val clock = TickWaitClock()
        clock.arrived(ms(1_000))
        assertEquals(125.0, clock.drawn(ms(1_125))!!, 0.001)
    }

    @Test
    fun `only the first picture since the last draw starts the wait`() {
        val clock = TickWaitClock()
        clock.arrived(ms(1_000))
        clock.arrived(ms(1_100)) // a newer one replaces it on the screen, but the wait is of the first
        assertEquals(240.0, clock.drawn(ms(1_240))!!, 0.001)
    }

    @Test
    fun `a draw with nothing waiting measures nothing, and a wait is counted once`() {
        val clock = TickWaitClock()
        assertNull(clock.drawn(ms(5)))
        clock.arrived(ms(10))
        clock.drawn(ms(20))
        assertNull("the second draw has no picture of its own waiting", clock.drawn(ms(30)))
    }

    @Test
    fun `the text is the mean, the 95th percentile and the worst`() {
        assertEquals("-", TickWait.text(emptyList()))
        assertEquals("125/250/250ms", TickWait.text(listOf(0.0, 250.0, 125.0, 125.0)))
        // 20 samples: the 95th percentile is the 19th in order; the worst is the 20th.
        val twenty = (1..20).map { it * 10.0 }
        assertEquals("105/190/200ms", TickWait.text(twenty))
    }

    @Test
    fun `the log hands over what was measured and starts again`() {
        val log = TickWaitLog()
        log.add(10.0)
        log.add(30.0)
        assertEquals(listOf(10.0, 30.0), log.take())
        assertEquals(emptyList<Double>(), log.take())
    }
}
