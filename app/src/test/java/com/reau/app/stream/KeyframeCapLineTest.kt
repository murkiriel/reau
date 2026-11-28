package com.reau.app.stream

import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `idrcap=` the `video fps` line ends with while the keyframe's size regulator is on ([com.reau.app.dash.video.KeyframeCap]); with it off the line is what it was. */
class KeyframeCapLineTest {
    private fun line(factor: Double?): String {
        var nowMs = 0L
        val g = StreamGovernor(4, bitrateCeilingBps = 400_000, keyframeSec = 1)
        g.start(nowMs)
        val actions = (0..40).flatMap {
            nowMs += 250L
            g.onPoll(
                StreamGovernor.Poll(
                    nowMs = nowMs,
                    compositor = DashCompositor.Stats(sourceFrames = 1, drawn = 1, repeated = 0, composeNs = 1_000_000L),
                    framesOut = 1, bytesOut = 1_000L, lateFrames = 0, encodeNs = 1_000_000L, dashAcks = 0, firstFrameConfirmed = true,
                    keyframeCapFactor = factor,
                ),
            )
        }
        return actions.filterIsInstance<StreamGovernor.Action.Log>().map { it.line }.first { it.startsWith("video fps") }
    }

    @Test
    fun `with the regulator on the line ends with the factor in force`() {
        assertTrue(line(0.82), line(0.82).endsWith(" idrcap=0.82"))
        assertTrue(line(1.0).endsWith(" idrcap=1.00"))
        assertTrue(line(0.4).endsWith(" idrcap=0.40"))
    }

    @Test
    fun `with it off the line has no such field, and with it on the line is the same one with the field after it`() {
        assertFalse(line(null), "idrcap" in line(null))
        assertTrue(line(null), line(null).substringAfterLast(' ').startsWith("wake="))
        assertEquals(line(null) + " idrcap=0.82", line(0.82))
    }
}
