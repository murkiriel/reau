package com.reau.app.dash

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 35 S: the window's `dash-stats` line says whether the packets are paced, what the pacer waited and the longest wait of a packet; with no pacer it is the line it always was. */
class DashStatsPaceTest {
    private val clock = AtomicLong(0L)
    private fun pacer(enabled: Boolean = true) = SendPacer(enabled, nanoTime = { clock.get() }, waitUntil = { clock.set(it) })

    @Test
    fun `with no pacer the line has no pace part`() {
        val line = DashStats().windowLine(5_000, 5_000)
        assertTrue(line, "pace" !in line)
    }

    @Test
    fun `a pacer that waited puts its window before last-rx and its total at the end`() {
        val stats = DashStats().also { it.pacer = pacer() }
        val p = checkNotNull(stats.pacer)
        repeat(3) { p.pace() } // waits of 0, 2 and 2 ms
        val line = stats.windowLine(5_000, 5_000)
        assertTrue(line, "quiet=" !in line && " pace=on waited=4.0ms max=2.0ms last-rx=" in line)
        assertTrue(line, line.endsWith(" pace-waited=4ms"))
    }

    @Test
    fun `the next window starts again and the total goes on`() {
        val stats = DashStats().also { it.pacer = pacer() }
        repeat(2) { checkNotNull(stats.pacer).pace() }
        stats.windowLine(5_000, 5_000)
        val second = stats.windowLine(5_000, 10_000)
        assertTrue(second, " pace=on waited=0.0ms max=0.0ms last-rx=" in second)
        assertTrue(second, second.endsWith(" pace-waited=2ms"))
    }

    @Test
    fun `a pacer that is off says so and has no total`() {
        val stats = DashStats().also { it.pacer = pacer(enabled = false) }
        val line = stats.windowLine(5_000, 5_000)
        assertTrue(line, " pace=off last-rx=" in line)
        assertEquals(false, "pace-waited" in line)
    }
}
