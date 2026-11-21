package com.reau.app.dash

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** [KeyframeQuiet]: how long a routine send waits so that it lands away from a keyframe's burst (ride log of 21/11/2026). */
class KeyframeQuietTest {
    private val quiet = KeyframeQuiet { clockMs }
    private var clockMs = 10_000L

    @Test
    fun `with no keyframe seen a routine send does not wait`() {
        assertEquals(0L, quiet.waitMs(10_000L))
        assertEquals(0L, quiet.waitMs(0L))
    }

    @Test
    fun `right after a keyframe the send waits for the rest of the 150 ms`() {
        quiet.keyframeEnded(5_000L)
        assertEquals(150L, quiet.waitMs(5_000L))
        assertEquals(100L, quiet.waitMs(5_050L))
        assertEquals(1L, quiet.waitMs(5_149L))
        assertEquals(0L, quiet.waitMs(5_150L))
        assertEquals(0L, quiet.waitMs(9_000L))
    }

    @Test
    fun `a keyframe going out makes the send wait the 150 ms and ask again`() {
        quiet.keyframeStarted(5_000L)
        assertEquals(150L, quiet.waitMs(5_000L))
        assertEquals(150L, quiet.waitMs(5_005L))
        quiet.keyframeEnded(5_008L)
        assertEquals(147L, quiet.waitMs(5_011L))
    }

    @Test
    fun `a keyframe that never ends does not hold the routine for good`() {
        quiet.keyframeStarted(5_000L)
        assertEquals(150L, quiet.waitMs(5_299L))
        assertEquals(0L, quiet.waitMs(5_300L))
        assertEquals(0L, quiet.waitMs(60_000L))
    }

    @Test
    fun `several keyframes in a row each move the quiet`() {
        quiet.keyframeEnded(1_000L)
        assertEquals(0L, quiet.waitMs(1_200L))
        quiet.keyframeEnded(2_000L)
        assertEquals(100L, quiet.waitMs(2_050L))
        quiet.keyframeStarted(3_000L)
        quiet.keyframeEnded(3_010L)
        assertEquals(140L, quiet.waitMs(3_020L))
        assertEquals(0L, quiet.waitMs(3_200L))
    }

    @Test
    fun `a clock reading before the keyframe never waits more than 150 ms`() {
        quiet.keyframeEnded(5_000L)
        assertEquals(150L, quiet.waitMs(4_000L))
    }

    @Test
    fun `await pauses for the wait, once, and says how long`() = runBlocking {
        quiet.keyframeEnded(10_000L)
        clockMs = 10_040L
        val pauses = mutableListOf<Long>()
        val waited = quiet.await { pauses += it; clockMs += it }
        assertEquals(listOf(110L), pauses)
        assertEquals(110L, waited)
    }

    @Test
    fun `await does not pause with no keyframe near`() = runBlocking {
        val pauses = mutableListOf<Long>()
        assertEquals(0L, quiet.await { pauses += it })
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun `await waits out a keyframe going out and never more than 300 ms`() = runBlocking {
        quiet.keyframeStarted(10_000L)
        val pauses = mutableListOf<Long>()
        // The clock does not move (a stuck keyframe, a test with no time): the cap is of what was waited.
        val waited = quiet.await { pauses += it }
        assertEquals(KeyframeQuiet.MAX_WAIT_MS, waited)
        assertEquals(listOf(150L, 150L), pauses)
    }

    @Test
    fun `await ends early when the keyframe ended meanwhile`() = runBlocking {
        quiet.keyframeStarted(10_000L)
        val pauses = mutableListOf<Long>()
        val waited = quiet.await {
            pauses += it
            clockMs += it
            if (pauses.size == 1) quiet.keyframeEnded(10_005L)
        }
        // First pause 150 (the burst going out); it ended at 10_005, so 5 ms more reach 150 ms after its end.
        assertEquals(listOf(150L, 5L), pauses)
        assertEquals(155L, waited)
    }
}
