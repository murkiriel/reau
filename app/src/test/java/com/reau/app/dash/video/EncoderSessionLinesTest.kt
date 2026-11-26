package com.reau.app.dash.video

import com.reau.app.util.CodecReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ride log says of the encoder at the opening of a stream, for both encoders alike (26/11/2026): the one in use and what the rider asked, the rate control asked, sent and
 * accepted, and the SPS as the encoder made it with its profile and level, and the slices the processor counts of each frame.
 */
class EncoderSessionLinesTest {
    private fun sps(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `the line of the encoder in use says what was asked, the name, hardware or software and the rate, and the note when there is one`() {
        assertEquals(
            "encoder in use: asked=PHONE name=c2.mtk.avc.encoder kind=hw rate=CBR -> VBR (CBR not offered)",
            EncoderReport.pickLine(VideoEncoder.PHONE, "c2.mtk.avc.encoder", hardware = true, rateSent = "CBR -> VBR (CBR not offered)", note = null),
        )
        assertEquals(
            "encoder in use: asked=SOFTWARE name=c2.android.avc.encoder kind=sw rate=CBR -> CBR; software encoder asked: c2.android.avc.encoder first",
            EncoderReport.pickLine(VideoEncoder.SOFTWARE, "c2.android.avc.encoder", hardware = false, rateSent = "CBR -> CBR", note = "software encoder asked: c2.android.avc.encoder first"),
        )
    }

    @Test
    fun `the fall back to the phone's own is in the line of the encoder in use`() {
        val line = EncoderReport.pickLine(VideoEncoder.SOFTWARE, "c2.mtk.avc.encoder", true, "CBR -> VBR (CBR not offered)", "software encoder asked, none in the phone's list: the phone's own is used")
        assertTrue(line, "asked=SOFTWARE name=c2.mtk.avc.encoder kind=hw" in line)
        assertTrue(line, line.endsWith("none in the phone's list: the phone's own is used"))
    }

    @Test
    fun `the rate line says what was asked and sent, and the mode the codec echoed`() {
        assertEquals("encoder rate: CBR -> VBR (CBR not offered), codec says VBR", EncoderReport.rateLine("CBR -> VBR (CBR not offered)", 1))
        assertEquals("encoder rate: CBR -> CBR, codec says CBR", EncoderReport.rateLine("CBR -> CBR", 2))
        assertEquals("a codec that echoes nothing", "encoder rate: CBR -> CBR, codec says not echoed", EncoderReport.rateLine("CBR -> CBR", null))
        assertEquals("CBR_FD", CodecReport.bitrateModeName(3))
    }

    @Test
    fun `the SPS line gives the original and the sent bytes, the profile and the level of the original`() {
        val original = sps(0x67, 0x42, 0x80, 0x29, 0x11)
        val sent = NalProcessor.spsForDash(original)
        assertEquals(
            "encoder sps: profile=Baseline(66) level=4.1 constraints=0x80; original=67 42 80 29 11; sent=67 42 00 29 11",
            SpsReport.line(original, sent),
        )
    }

    @Test
    fun `the profile and the level are read from the bytes, and a short or unknown one is not made up`() {
        assertEquals("High(100)", SpsReport.profile(sps(0x67, 100, 0, 40)))
        assertEquals("Main(77)", SpsReport.profile(sps(0x67, 77, 0, 40)))
        assertEquals("99", SpsReport.profile(sps(0x67, 99, 0, 40)))
        assertEquals("3.1", SpsReport.level(sps(0x67, 0x42, 0, 31)))
        assertEquals("1b", SpsReport.level(sps(0x67, 0x42, 0, 9)))
        assertEquals("?", SpsReport.profile(sps(0x67)))
        assertEquals("?", SpsReport.level(sps(0x67, 0x42, 0)))
        assertTrue(SpsReport.line(sps(0x67), sps(0x67)).startsWith("encoder sps: profile=? level=? constraints=?;"))
    }

    @Test
    fun `the processor keeps the SPS as the encoder made it and the one it sends, and says nothing before the first`() {
        val processor = NalProcessor { _, _ -> }
        assertNull(processor.originalSps)
        assertNull(processor.sentSps)
        processor.process(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x80.toByte(), 0x29, 0x11))
        assertEquals(listOf(0x67, 0x42, 0x80, 0x29, 0x11), processor.originalSps!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x67, 0x42, 0x00, 0x29, 0x11), processor.sentSps!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `the slices of a call are the NAL units of types 1 to 5, counted again at each call`() {
        val processor = NalProcessor { _, _ -> }
        val start = byteArrayOf(0, 0, 0, 1)
        processor.process(start + byteArrayOf(0x67, 0x42, 0, 0x29) + start + byteArrayOf(0x68, 1) + start + byteArrayOf(0x65, 1))
        assertEquals("the parameter sets are no slices", 1, processor.lastSlices)
        processor.process(start + byteArrayOf(0x41, 1) + start + byteArrayOf(0x41, 2) + start + byteArrayOf(0x41, 3))
        assertEquals("a picture of three slices", 3, processor.lastSlices)
        processor.process(start + byteArrayOf(0x06, 1) + start + byteArrayOf(0x09, 1))
        assertEquals("SEI and AUD are none", 0, processor.lastSlices)
        assertFalse(processor.lastSlices > 1)
    }
}
