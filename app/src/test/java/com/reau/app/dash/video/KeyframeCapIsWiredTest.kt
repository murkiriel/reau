package com.reau.app.dash.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regulator of the keyframe's size is fed and applied where the encoder's output is: each keyframe's bytes reach it, what it says goes to the codec as the other
 * bitrate requests times its factor, the ride log hears of it, and the stream's loop builds every encoder with the one regulator the rider's switch made.
 * What the regulator does with the numbers is [KeyframeCapTest]'s. (The codec is not on the JVM: these read the source, as the project does for its wiring.)
 */
class KeyframeCapIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()
    private val encoder = source("dash/video/DashEncoder.kt")
    private val loop = source("stream/VideoLoop.kt")

    private fun String.between(from: String, to: String) = substringAfter(from).substringBefore(to)

    @Test
    fun `each keyframe that comes out is told to the regulator with the bytes that go to the dash, after it was passed on`() {
        val emit = encoder.between("private fun emitOutput(", "fun requestBitrate")
        assertTrue(emit, "if (isFrame && isKeyFrame(info.flags)) regulateKeyframe(data)" in emit)
        assertTrue("after the send, so the keyframe is not held back by the regulator", emit.indexOf("onFrame(data, isKeyFrame(info.flags), lastPtsMs)") in 0 until emit.indexOf("regulateKeyframe(data)"))
        assertTrue("the SPS/PPS buffer's size is kept for the sum", "parameterSetBytes = data.size" in emit && "BUFFER_FLAG_CODEC_CONFIG" in emit)
        val regulate = encoder.between("private fun regulateKeyframe(", "private fun setBitrate(")
        assertTrue(regulate, "keyframeCap.onKeyframe(KeyframeCap.sentBytes(data, parameterSetBytes)) ?: return" in regulate)
        assertTrue(regulate, "RideDiagnostics.log(\"stream\", KeyframeCap.changeLine(change, bps))" in regulate)
    }

    @Test
    fun `what is asked of the codec is the others' bitrate times the factor, at the start, at each request and at each change`() {
        val request = encoder.between("fun requestBitrate(", "private fun regulateKeyframe(")
        assertTrue(request, "baseBps = bps" in request && "applyBitrate()" in request)
        val apply = encoder.between("private fun applyBitrate()", "private fun setBitrate(")
        assertTrue(apply, "val bps = keyframeCap.effective(baseBps)" in apply && "setBitrate(bps)" in apply)
        assertTrue("one thread at a time asks the codec, so the older value is never left behind", "@Synchronized\n    private fun applyBitrate()" in encoder)
        val regulate = encoder.between("private fun regulateKeyframe(", "private fun applyBitrate()")
        assertTrue(regulate, "val bps = applyBitrate()" in regulate)
        val prepare = encoder.between("    fun prepare(", "val names: List<String?>")
        assertTrue(prepare, "baseBps = bitrateBps" in prepare)
        assertTrue("a rebuilt encoder starts at the factor held", "val ask = Ask(fpsHint, keyframeCap.effective(bitrateBps), iFrameIntervalSec, frame)" in prepare)
        val live = encoder.substringAfter("private fun setBitrate(").substringBefore("fun requestSyncFrame")
        assertTrue(live, "putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)" in live)
    }

    @Test
    fun `the encoder is built with no regulator by default, which asks exactly what it is asked`() {
        assertTrue("private val keyframeCap: KeyframeCap = KeyframeCap(enabled = false)," in encoder)
        assertEquals("the one place the codec is asked its bitrate live", 1, Regex("""putInt\(MediaCodec\.PARAMETER_KEY_VIDEO_BITRATE""").findAll(encoder).count())
        assertFalse("no quantizer is asked of the codec (the MediaTek one ignores it)", "KEY_VIDEO_QP" in encoder || "PARAMETER_KEY_VIDEO_QP" in encoder)
    }

    @Test
    fun `the loop makes one regulator for the stream from the switch and gives it to every encoder it builds`() {
        assertTrue("private var keyframeCap = KeyframeCap(enabled = false)" in loop)
        val start = loop.between("fun start(): Boolean {", "val status = thermalStatus()")
        assertTrue(start, "keyframeCap = KeyframeCap(settings.keyframeCap)" in start)
        assertTrue("created = DashEncoder(settings.frame, keyframeCap) {" in loop)
        assertEquals("only the loop builds the encoder", 1, Regex("""DashEncoder\(""").findAll(loop).count())
        assertTrue("keyframeCap = rider.keyframeCap," in loop)
    }

    @Test
    fun `the other bitrate requests are the governor's through the loop, and the cap does not go round them`() {
        assertEquals("the loop's apply is the only caller of requestBitrate", 1, Regex("""\.requestBitrate\(""").findAll(loop).count())
        assertTrue("encoder?.requestBitrate(action.bps)" in loop)
        val others = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "DashEncoder.kt" }.filter { "requestBitrate(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("VideoLoop.kt"), others)
    }

    @Test
    fun `the window's line says idrcap at its end while the regulator is on, and the poll hands it the factor`() {
        assertTrue("keyframeCapFactor = keyframeCap.takeIf { it.enabled }?.factor," in loop)
        val governor = source("stream/StreamGovernor.kt")
        assertTrue(governor, "(p.keyframeCapFactor?.let { String.format(Locale.US, \" idrcap=%.2f\", it) } ?: \"\")" in governor)
    }

    @Test
    fun `the setting is read when a stream is made, from the rider's settings, and a change restarts nothing`() {
        val live = source("service/LiveSides.kt")
        assertEquals("a projection starts with the rider's settings and a new stream takes them up again", 2, Regex("""\.withRider\(settings\)""").findAll(live).count())
        assertEquals(emptySet<com.reau.app.service.LiveTuning.Step>(), com.reau.app.service.LiveTuning.steps(com.reau.app.settings.REauSettings(), com.reau.app.settings.REauSettings(keyframeCap = false)))
    }
}
