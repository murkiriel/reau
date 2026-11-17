package com.reau.app.dash.video

import android.media.MediaCodecInfo.EncoderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Spec = DashEncoder.Companion.FormatSpec

/**
 * The Qualcomm encoder's low-latency key, on the experimental switch: only a Qualcomm encoder is asked, only with the switch on, and a codec that refuses
 * the format with it is asked again without it. The ladder of [DashEncoder.startNamed] is run with a fake codec factory that records each format asked.
 */
class VendorLowLatencyTest {
    private val cbr = DashEncoder.RateMode(EncoderCapabilities.BITRATE_MODE_CBR, "CBR")
    private val detail = "fpsHint=4"

    private fun ask(switch: Boolean, color: Boolean = false) = DashEncoder.Companion.Ask(4, 400_000, 1, color, vendorLowLatency = switch)

    /** One format asked of the factory, as a list: low-latency keys, bitrate mode, colour keys, the maker's key. */
    private fun Spec.fields() = listOf(lowLatency, bitrateMode, color, vendorLowLatency)

    /** What a run of [DashEncoder.startNamed] asked of the factory, which refuses every format for which [refuses] says so. */
    private class Run(val specs: List<Spec>, val details: List<String>, val result: Result<String>)

    private fun ladder(
        name: String?, ask: DashEncoder.Companion.Ask, rate: DashEncoder.RateMode = cbr,
        refuses: (Spec) -> Boolean = { false },
    ): Run {
        val specs = mutableListOf<Spec>()
        val details = mutableListOf<String>()
        val result = DashEncoder.startNamed(name, ask, rate, detail) { _, spec, d ->
            specs += spec
            details += d
            if (refuses(spec)) throw IllegalArgumentException("refused") else "started"
        }
        return Run(specs, details, result)
    }

    // ── which encoders are Qualcomm's ──────────────────────────────────────────────────────────────────

    @Test
    fun `the encoders of Qualcomm are told by their names, and nothing else is`() {
        assertTrue(VendorLowLatency.isQualcomm("c2.qti.avc.encoder"))
        assertTrue(VendorLowLatency.isQualcomm("OMX.qcom.video.encoder.avc"))
        assertFalse(VendorLowLatency.isQualcomm("c2.android.avc.encoder"))
        assertFalse(VendorLowLatency.isQualcomm("OMX.google.h264.encoder"))
        assertFalse(VendorLowLatency.isQualcomm("c2.exynos.h264.encoder"))
        assertFalse("only a prefix counts", VendorLowLatency.isQualcomm("x.c2.qti.avc.encoder"))
        assertFalse("the dot after qti is part of the prefix", VendorLowLatency.isQualcomm("c2.qtiavc.encoder"))
        assertFalse("the system's own pick has no name yet", VendorLowLatency.isQualcomm(null))
        assertFalse(VendorLowLatency.isQualcomm(""))
    }

    @Test
    fun `the key is asked only with the switch on and a Qualcomm encoder`() {
        assertTrue(VendorLowLatency.asked(true, "c2.qti.avc.encoder"))
        assertFalse(VendorLowLatency.asked(false, "c2.qti.avc.encoder"))
        assertFalse(VendorLowLatency.asked(true, "c2.android.avc.encoder"))
        assertFalse(VendorLowLatency.asked(true, null))
    }

    @Test
    fun `the key is the maker's, set to 1, and asked only when it is`() {
        assertEquals(mapOf("vendor.qti-ext-enc-low-latency.enable" to 1), VendorLowLatency.keysFor(true))
        assertEquals(emptyMap<String, Int>(), VendorLowLatency.keysFor(false))
        assertEquals("vendor.qti-ext-enc-low-latency.enable", VendorLowLatency.KEY)
    }

    @Test
    fun `the encoder line says nothing with the switch off, and with it on says whether the key was asked`() {
        assertEquals("", VendorLowLatency.tag(false, "c2.qti.avc.encoder"))
        assertEquals("", VendorLowLatency.tag(false, "c2.android.avc.encoder"))
        assertEquals(" qcLowLatency=on", VendorLowLatency.tag(true, "c2.qti.avc.encoder"))
        assertEquals(" qcLowLatency=skipped (not a Qualcomm encoder)", VendorLowLatency.tag(true, "c2.android.avc.encoder"))
        assertEquals(" qcLowLatency=skipped (not a Qualcomm encoder)", VendorLowLatency.tag(true, null))
    }

    // ── the ladder ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `switch off, even a Qualcomm encoder gets the format it always did, and the ladder of before`() {
        val first = ladder("c2.qti.avc.encoder", ask(false))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, false)), first.specs.map { it.fields() })
        assertEquals(listOf("fpsHint=4 lowLatency=on"), first.details)
        assertEquals("started", first.result.getOrThrow())

        val refused = ladder("c2.qti.avc.encoder", ask(false)) { true }
        assertEquals(
            listOf(
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, false),
                listOf(false, EncoderCapabilities.BITRATE_MODE_CBR, false, false),
                listOf(false, null, false, false),
            ),
            refused.specs.map { it.fields() },
        )
        assertEquals(
            listOf(
                "fpsHint=4 lowLatency=on",
                "fpsHint=4 lowLatency=fallback (IllegalArgumentException)",
                "fpsHint=4 lowLatency=fallback bitrateMode=fallback (IllegalArgumentException)",
            ),
            refused.details,
        )
        assertTrue(refused.result.isFailure)
    }

    @Test
    fun `switch on with a Qualcomm encoder, the key goes in the first format and the line says so`() {
        val r = ladder("c2.qti.avc.encoder", ask(true))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, true)), r.specs.map { it.fields() })
        assertEquals(listOf("fpsHint=4 lowLatency=on qcLowLatency=on"), r.details)
        val omx = ladder("OMX.qcom.video.encoder.avc", ask(true))
        assertTrue(omx.specs.single().vendorLowLatency)
    }

    @Test
    fun `switch on with another encoder, the key is in no format of the ladder`() {
        for (name in listOf("c2.android.avc.encoder", "c2.exynos.h264.encoder", null)) {
            val r = ladder(name, ask(true)) { true }
            assertEquals("$name", 3, r.specs.size)
            assertTrue("$name: ${r.specs.map { it.fields() }}", r.specs.none { it.vendorLowLatency })
            assertEquals("$name", "fpsHint=4 lowLatency=on qcLowLatency=skipped (not a Qualcomm encoder)", r.details.first())
        }
        val plain = ladder("c2.android.avc.encoder", ask(true))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, false)), plain.specs.map { it.fields() })
    }

    @Test
    fun `a Qualcomm encoder that refuses the key is asked again with the realtime keys and without it`() {
        val r = ladder("c2.qti.avc.encoder", ask(true)) { it.vendorLowLatency }
        assertEquals(
            listOf(
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, true),
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, false),
            ),
            r.specs.map { it.fields() },
        )
        assertEquals(
            listOf("fpsHint=4 lowLatency=on qcLowLatency=on", "fpsHint=4 lowLatency=on qcLowLatency=fallback (IllegalArgumentException)"),
            r.details,
        )
        assertEquals("started", r.result.getOrThrow())
    }

    @Test
    fun `a codec that refuses the realtime keys too still falls down the old ladder, with no key left in any format`() {
        val r = ladder("c2.qti.avc.encoder", ask(true)) { it.lowLatency }
        assertEquals(
            listOf(
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, true),
                listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, false, false),
                listOf(false, EncoderCapabilities.BITRATE_MODE_CBR, false, false),
            ),
            r.specs.map { it.fields() },
        )
        assertTrue(r.specs.drop(1).none { it.vendorLowLatency })
        assertEquals("started", r.result.getOrThrow())
        val everything = ladder("c2.qti.avc.encoder", ask(true)) { true }
        assertEquals(listOf(true, false, false, false), everything.specs.map { it.vendorLowLatency })
        assertTrue(everything.result.isFailure)
    }

    @Test
    fun `with no bitrate mode asked the ladder has no step for it, key or not`() {
        val none = DashEncoder.RateMode(null, "default")
        val r = ladder("c2.qti.avc.encoder", ask(true), none) { true }
        assertEquals(listOf(listOf(true, null, false, true), listOf(true, null, false, false), listOf(false, null, false, false)), r.specs.map { it.fields() })
        assertTrue(r.result.isFailure)
    }

    @Test
    fun `with the colour keys asked as well, the first format has both and a refusal goes back to the ladder`() {
        val both = ladder("c2.qti.avc.encoder", ask(true, color = true))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, true, true)), both.specs.map { it.fields() })
        assertEquals("fpsHint=4 lowLatency=on color=${ColorMatrix.ASKED} qcLowLatency=on", both.details.single())
        val other = ladder("c2.android.avc.encoder", ask(true, color = true))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, true, false)), other.specs.map { it.fields() })
        val off = ladder("c2.qti.avc.encoder", ask(false, color = true))
        assertEquals(listOf(listOf(true, EncoderCapabilities.BITRATE_MODE_CBR, true, false)), off.specs.map { it.fields() })
        assertEquals("fpsHint=4 lowLatency=on color=${ColorMatrix.ASKED}", off.details.single())
    }

    // ── the log ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the accepted line of the log carries the key when it was asked, echoed or not`() {
        val key = VendorLowLatency.KEY
        assertTrue("the report reads it", key in com.reau.app.util.CodecReport.KEYS)
        val asked = mapOf<String, Number>(key to 1, "latency" to 1)
        assertEquals("latency=1 $key=1", com.reau.app.util.CodecReport.accepted(asked) { mapOf<String, Number>(key to 1, "latency" to 1)[it] })
        assertEquals("nothing echoed; not echoed: latency, $key", com.reau.app.util.CodecReport.accepted(asked) { null })
        assertEquals(
            "$key=0; differs: $key asked 1 got 0",
            com.reau.app.util.CodecReport.accepted(mapOf<String, Number>(key to 1)) { if (it == key) 0 else null },
        )
    }

    @Test
    fun `without the key asked, the accepted line does not mention it`() {
        val text = com.reau.app.util.CodecReport.accepted(mapOf<String, Number>("latency" to 1)) { if (it == "latency") 1 else null }
        assertEquals("latency=1", text)
        assertNull(Regex("vendor").find(text))
    }
}
