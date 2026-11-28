package com.reau.app.dash.video

import android.media.MediaCodecInfo.EncoderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private typealias Spec = DashEncoder.Companion.FormatSpec

/**
 * The ladder of formats [DashEncoder.startNamed] asks one encoder: the realtime keys with the bitrate mode, then without the realtime keys, then without the
 * bitrate mode. Two rungs that sat above it behind experimental switches (the colour keys, the Qualcomm maker's low-latency key) went on 20/11/2026: the
 * ladder is the one a stream had with both off, and the format carries neither key. It is run with a fake codec factory that records each format asked.
 */
class EncoderLadderTest {
    private val cbr = DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_CBR, "CBR")
    private val detail = "fpsHint=4"

    private class Run(val specs: List<Spec>, val details: List<String>, val result: Result<String>)

    private fun ladder(name: String?, rate: DashEncoder.RateMode = cbr, refuses: (Spec) -> Boolean = { false }): Run {
        val specs = mutableListOf<Spec>()
        val details = mutableListOf<String>()
        val result = DashEncoder.startNamed(name, rate, detail) { _, spec, d ->
            specs += spec
            details += d
            if (refuses(spec)) throw IllegalArgumentException("refused") else "started"
        }
        return Run(specs, details, result)
    }

    private fun Spec.fields() = listOf(lowLatency, bitrateMode)

    @Test
    fun `a codec that takes the first format is asked once, whoever made it`() {
        for (name in listOf("c2.qti.avc.encoder", "OMX.qcom.video.encoder.avc", "c2.android.avc.encoder", "c2.exynos.h264.encoder", null)) {
            val r = ladder(name)
            assertEquals("$name", listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR)), r.specs.map { it.fields() })
            assertEquals("$name", listOf("fpsHint=4 lowLatency=on"), r.details)
            assertEquals("started", r.result.getOrThrow())
        }
    }

    @Test
    fun `a codec that refuses the realtime keys is asked again without them, then without the bitrate mode`() {
        val r = ladder("c2.qti.avc.encoder") { true }
        assertEquals(
            listOf(
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR),
                listOf(false, EncoderCapabilities.BITRATE_MODE_CBR),
                listOf(false, null),
            ),
            r.specs.map { it.fields() },
        )
        assertEquals(
            listOf(
                "fpsHint=4 lowLatency=on",
                "fpsHint=4 lowLatency=fallback (IllegalArgumentException)",
                "fpsHint=4 lowLatency=fallback bitrateMode=fallback (IllegalArgumentException)",
            ),
            r.details,
        )
        assertTrue(r.result.isFailure)
    }

    @Test
    fun `a codec that takes the plain format after refusing the keys ends the ladder there`() {
        val r = ladder("c2.android.avc.encoder") { it.lowLatency }
        assertEquals(2, r.specs.size)
        assertEquals("started", r.result.getOrThrow())
    }

    @Test
    fun `with no bitrate mode asked the ladder has no step for it`() {
        val none = DashEncoder.RateMode(null, "default")
        val r = ladder("c2.qti.avc.encoder", none) { true }
        assertEquals(listOf(listOf(true, null), listOf(false, null)), r.specs.map { it.fields() })
        assertTrue(r.result.isFailure)
    }

    @Test
    fun `no rung carries a colour key or a maker's key - the format and the encoder's code have none`() {
        val encoder = File("src/main/java/com/reau/app/dash/video/DashEncoder.kt").readText()
        assertFalse(encoder.contains("ColorMatrix") || encoder.contains("VendorLowLatency") || encoder.contains("vendor.qti") || encoder.contains("KEY_COLOR_STANDARD"))
        assertTrue(encoder.contains("internal class FormatSpec(val lowLatency: Boolean, val bitrateMode: Int?)"))
        assertTrue(encoder.contains("Ask(fpsHint, keyframeCap.effective(bitrateBps), iFrameIntervalSec, frame)"))
        assertFalse(File("src/main/java/com/reau/app/util/CodecReport.kt").readText().contains("vendor.qti"))
        assertFalse(File("src/main/java/com/reau/app/dash/video/EncoderReport.kt").readText().contains("colour"))
    }
}
