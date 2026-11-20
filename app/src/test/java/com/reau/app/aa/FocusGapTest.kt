package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The measure of what a focus cycle bought (the keyframe that follows it), and the gap of the cycle: 100 ms, as REau always used. A longer gap
 * (400 ms) was tried behind a switch until 20/11/2026 and is gone.
 */
class FocusGapTest {
    @Test
    fun `the gap of the focus cycle is the 100 ms REau always used`() {
        assertEquals(100L, FocusGap.DEFAULT_MS)
    }

    @Test
    fun `the first keyframe after a cycle says how long it took, once`() {
        val timing = FocusCycleTiming()
        assertNull("no cycle, nothing to say", timing.keyframe(500))
        timing.cycled(1_000, gapMs = 400)
        assertEquals("focus cycle: the keyframe came 730 ms after the cycle (focus given back after 400 ms)", timing.keyframe(1_730))
        assertNull("the next keyframe is an ordinary one", timing.keyframe(2_730))
    }

    @Test
    fun `a cycle that brings no keyframe in 5 s is told once, and a late keyframe still says its time`() {
        val timing = FocusCycleTiming()
        timing.cycled(1_000, gapMs = 100)
        assertNull(timing.overdue(5_999))
        assertEquals("focus cycle: no keyframe 5000 ms after the cycle (focus given back after 100 ms)", timing.overdue(6_000))
        assertNull("once", timing.overdue(7_000))
        assertEquals("focus cycle: the keyframe came 8000 ms after the cycle (focus given back after 100 ms)", timing.keyframe(9_000))
        assertNull(timing.overdue(20_000))
    }

    @Test
    fun `a new cycle starts the measure over`() {
        val timing = FocusCycleTiming()
        timing.cycled(1_000, gapMs = 100)
        timing.overdue(6_000)
        timing.cycled(10_000, gapMs = 400)
        assertEquals("focus cycle: no keyframe 5000 ms after the cycle (focus given back after 400 ms)", timing.overdue(15_000))
    }

    @Test
    fun `nothing is overdue with no cycle`() {
        assertNull(FocusCycleTiming().overdue(1_000_000))
    }

    @Test
    fun `the transport uses the gap for the focus given back, with the cycle's line as it always was, and the keyframes reach the measure`() {
        val transport = File("src/main/java/com/reau/app/aa/AapTransport.kt").readText()
        val cycle = transport.substringAfter("private fun cycleVideoFocus()").substringBefore("internal fun keyframeArrived()")
        assertTrue(cycle.contains("val gapMs = FocusGap.DEFAULT_MS"))
        assertTrue(cycle.contains("}, gapMs)"))
        assertTrue(cycle.contains("\"AapTransport: Requesting recovery keyframe via focus cycle.\""))
        assertFalse("no switch is read for it", transport.contains("focusGapMs"))
        assertFalse("the fixed 100 ms is gone from the transport", transport.contains("FOCUS_GAIN_DELAY_MS"))
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertTrue(receiver.contains("stats.onKeyframe = transport::keyframeArrived"))
        assertFalse(receiver.contains("focusGapMs"))
    }

    @Test
    fun `the statistics tell of each keyframe, and only of keyframes`() {
        val stats = AaStats()
        var told = 0
        stats.onKeyframe = { told++ }
        stats.unit(100, keyframe = false)
        stats.unit(9_000, keyframe = true)
        stats.unit(100, keyframe = false)
        assertEquals(1, told)
    }
}
