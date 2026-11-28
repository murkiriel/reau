package com.reau.app.stream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line about the shape of a stream (26/11/2026): the keyframes' size on average and at most, in bytes and RTP packets, the slices of a frame and the worst second, which is
 * what tells the MediaTek stream from the Qualcomm one in the pilots' logs. The sums are done here with small numbers that can be checked by hand.
 */
class StreamShapeTest {
    private fun shape() = StreamShape()

    @Test
    fun `before the first frame there is no line`() {
        assertNull(shape().line())
        assertNull(shape().periodicLine(10_000_000))
    }

    @Test
    fun `the keyframes' average and maximum, in bytes and in packets`() {
        val s = shape()
        s.add(0, bytes = 30_000, packets = 22, key = true, slices = 1)
        s.add(166, bytes = 2_000, packets = 2, key = false, slices = 1)
        s.add(1_000, bytes = 40_000, packets = 29, key = true, slices = 1)
        s.add(1_166, bytes = 2_000, packets = 2, key = false, slices = 1)
        val line = s.line()!!
        assertTrue(line, line.startsWith("video shape: frames=4 keyframes=2 keybytes avg=35000 max=40000 keypackets avg=25.5 max=29 "))
    }

    @Test
    fun `a stream with no keyframe yet says zero and does not divide by zero`() {
        val s = shape()
        s.add(0, 2_000, 2, key = false, slices = 1)
        assertTrue(s.line()!!, "keyframes=0 keybytes avg=0 max=0 keypackets avg=0.0 max=0" in s.line()!!)
    }

    @Test
    fun `a stream of one slice a frame says slices 1, and one with more says the most and how many frames had more`() {
        val one = shape()
        repeat(5) { one.add(it * 166L, 1_000, 1, key = it == 0, slices = 1) }
        assertTrue(one.line()!!, " slices=1 peak1s=" in one.line()!!)

        val many = shape()
        many.add(0, 1_000, 1, key = true, slices = 1)
        many.add(166, 1_000, 1, key = false, slices = 3)
        many.add(332, 1_000, 1, key = false, slices = 2)
        many.add(498, 1_000, 1, key = false, slices = 1)
        assertTrue(many.line()!!, " slices=3 multi=2/4 peak1s=" in many.line()!!)
    }

    @Test
    fun `the worst second is the most that left in any 1 s window, in kbps and in packets`() {
        val s = shape()
        // A quiet second, then a burst (a keyframe and two frames in 600 ms), then quiet again.
        s.add(0, 1_000, 1, key = false, slices = 1)
        s.add(2_000, 30_000, 22, key = true, slices = 1)
        s.add(2_300, 4_000, 3, key = false, slices = 1)
        s.add(2_600, 4_000, 3, key = false, slices = 1)
        s.add(4_000, 1_000, 1, key = false, slices = 1)
        // 38 000 bytes in the burst's window: 38 000 x 8 / 1000 s = 304 kbps, in 28 packets.
        assertTrue(s.line()!!, s.line()!!.endsWith(" peak1s=304kbps/28pkt"))
    }

    @Test
    fun `a frame exactly one second back is out of the window, one a millisecond less is in`() {
        val out = shape()
        out.add(0, 10_000, 8, key = true, slices = 1)
        out.add(1_000, 10_000, 8, key = false, slices = 1)
        assertTrue(out.line()!!, out.line()!!.endsWith(" peak1s=80kbps/8pkt"))
        val inside = shape()
        inside.add(0, 10_000, 8, key = true, slices = 1)
        inside.add(999, 10_000, 8, key = false, slices = 1)
        assertTrue(inside.line()!!, inside.line()!!.endsWith(" peak1s=160kbps/16pkt"))
    }

    @Test
    fun `the line comes again every 5 minutes of stream, counted from the first frame, and says the totals so far`() {
        val s = shape()
        s.add(10_000, 5_000, 4, key = true, slices = 1)
        assertNull(s.periodicLine(309_999))
        s.add(310_000, 5_000, 4, key = false, slices = 1)
        val first = s.periodicLine(310_000)
        assertTrue(first!!, first.startsWith("video shape: frames=2 keyframes=1 "))
        assertNull("the clock for the next one starts here", s.periodicLine(500_000))
        assertTrue(s.periodicLine(610_000) != null)
        assertEquals(300_000L, StreamShape.SUMMARY_EVERY_MS)
    }

    @Test
    fun `the output feeds the shape from what really went out, with the slices of each buffer, and the video loop writes the line at the end and every 5 minutes`() {
        val output = File("src/main/java/com/reau/app/stream/RtpOutput.kt").readText()
        assertTrue(output.contains("shape.add(now, annexB.size, packetsMade - packetsBefore, isKey, nalProcessor.lastSlices)"))
        assertTrue(output.contains("shape.periodicLine(now)?.let { log?.invoke(it) }"))
        val loop = File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText()
        assertTrue(loop.contains("output?.shapeLine()?.let(log)"))
        assertTrue("the output is given the ride log", loop.contains("log = log,"))
        assertTrue("the end line comes right after the pace one, before the loop's own stop line", loop.indexOf("ending.paceSummary()?.let(log)") < loop.indexOf("output?.shapeLine()?.let(log)"))
        assertTrue(loop.indexOf("output?.shapeLine()?.let(log)") < loop.indexOf("log(\"video loop stop"))
    }
}
