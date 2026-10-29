package com.reau.app.dash.video

import com.reau.app.dash.video.EncoderOrder.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit of 20/10/2026: a phone whose first hardware encoder refused 526×300 was left with no picture and no other encoder tried. */
class EncoderOrderTest {
    private fun c(name: String, hardware: Boolean = true, size: Boolean? = true, baseline: Boolean? = true) =
        Candidate(name, hardware, size, baseline)

    @Test
    fun `hardware encoders come first, each group in the order the phone lists them`() {
        val order = EncoderOrder.names(listOf(c("sw.a", hardware = false), c("hw.a"), c("sw.b", hardware = false), c("hw.b")))
        assertEquals(listOf("hw.a", "hw.b", "sw.a", "sw.b"), order)
    }

    @Test
    fun `an encoder that says it does not take the size is left out, and the next one is tried`() {
        val order = EncoderOrder.names(listOf(c("hw.small", size = false), c("hw.ok"), c("sw.ok", hardware = false)))
        assertEquals(listOf("hw.ok", "sw.ok"), order)
    }

    @Test
    fun `an encoder with no Baseline profile is left out`() {
        assertEquals(listOf("hw.baseline"), EncoderOrder.names(listOf(c("hw.high-only", baseline = false), c("hw.baseline"))))
    }

    @Test
    fun `an encoder whose capabilities cannot be read is still worth a try`() {
        val order = EncoderOrder.names(listOf(c("hw.unknown", size = null, baseline = null), c("sw.known", hardware = false)))
        assertEquals(listOf("hw.unknown", "sw.known"), order)
    }

    @Test
    fun `when every encoder is ruled out the answer is empty, and the system's own pick is the last try`() {
        assertEquals(emptyList<String>(), EncoderOrder.names(listOf(c("a", size = false), c("b", baseline = false))))
        assertEquals(emptyList<String>(), EncoderOrder.names(emptyList()))
    }
}
