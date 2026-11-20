package com.reau.app.video

import com.reau.app.dash.DashConfig
import com.reau.app.settings.REauSettings
import com.reau.app.stream.VideoLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The smaller picture of the 12 fps profile (plan 32, phase 2): the size of the encoder by profile, the arithmetic that shrinks the dash's
 * 526x300 frame to it on the way out, the fall back to the full frame when no encoder takes it, and the lines that wire all of it (the
 * encoder, the OpenGL output and the video loop need Android, so their wiring is read from the source, as the `*IsWiredTest` tests do).
 */
class SmallPictureTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun at(pace: Int) = REauSettings(videoPaceFps = pace).sanitized(debugBuild = true)

    // ---- the size by profile

    @Test
    fun `the full frame is the dash's 526x300 and the small one 368x208, multiples of 16 and of nearly the same shape`() {
        assertEquals(listOf(526, 300), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals("the compositor's frame is the encoder's", listOf(DashGlass.FRAME_W, DashGlass.FRAME_H), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals(listOf(368, 208), listOf(DashConfig.REDUCED_FRAME.width, DashConfig.REDUCED_FRAME.height))
        assertEquals(0, DashConfig.REDUCED_FRAME.width % 16)
        assertEquals(0, DashConfig.REDUCED_FRAME.height % 16)
        val full = DashConfig.FULL_FRAME.width.toDouble() / DashConfig.FULL_FRAME.height
        val small = DashConfig.REDUCED_FRAME.width.toDouble() / DashConfig.REDUCED_FRAME.height
        assertEquals("the dash stretches it, so the shape must hardly change", full, small, 0.02)
    }

    @Test
    fun `only the 12 fps profile has the small picture, both A and B, and every other profile the full frame`() {
        for (p in DashConfig.STREAM_PROFILES) {
            val wanted = if (p.fullFps == 12) DashConfig.REDUCED_FRAME else DashConfig.FULL_FRAME
            assertEquals("profile of ${p.fullFps} fps", wanted, p.frame)
            assertEquals("the settings say the same", wanted, at(p.fullFps).frameSize)
        }
        assertEquals("a value outside the table reads as the default", DashConfig.FULL_FRAME, DashConfig.streamProfile(10).frame)
    }

    @Test
    fun `the picture follows the ceiling the stream is made with, not the pace that was asked for`() {
        // the settings screen and the stream read the ceiling (fpsCeiling); a pace asked for and not applied (a release build) must not change the picture
        assertEquals(DashConfig.FULL_FRAME, REauSettings(videoPaceFps = 12, fpsCeiling = 4).frameSize)
        assertEquals(DashConfig.REDUCED_FRAME, REauSettings(videoPaceFps = 4, fpsCeiling = 12).frameSize)
    }

    @Test
    fun `in a release build the picture is always the full frame, whatever was stored`() {
        for (pace in listOf(4, 6, 8, 12)) assertEquals("pace $pace", DashConfig.FULL_FRAME, REauSettings(videoPaceFps = pace).sanitized(debugBuild = false).frameSize)
        assertEquals(DashConfig.FULL_FRAME, REauSettings().sanitized(true).frameSize)
    }

    @Test
    fun `the video loop gets the picture of the rider's profile`() {
        val loop = VideoLoop.Settings()
        assertEquals(DashConfig.FULL_FRAME, loop.frame)
        assertEquals(DashConfig.FULL_FRAME, loop.withRider(at(8)).frame)
        assertEquals(DashConfig.REDUCED_FRAME, loop.withRider(at(12)).frame)
        assertEquals(DashConfig.FULL_FRAME, loop.withRider(REauSettings(videoPaceFps = 12).sanitized(debugBuild = false)).frame)
    }

    // ---- the fall back when no encoder takes the small picture

    @Test
    fun `without an encoder for the small picture the stream is the 8 fps profile at the full frame`() {
        val small = VideoLoop.Settings().withRider(at(12))
        val back = small.atFullFrame()
        assertEquals(listOf(8, 600_000, 300_000), listOf(back.ceilingFps, back.bitrateBps, back.reducedBitrateBps))
        assertEquals(DashConfig.FULL_FRAME, back.frame)
        assertEquals("nothing else of the settings moves", small.copy(ceilingFps = 8, bitrateBps = 600_000, reducedBitrateBps = 300_000, frame = DashConfig.FULL_FRAME), back)
        assertEquals("what the 8 fps profile is for the rider", VideoLoop.Settings().withRider(at(8)), back)
    }

    @Test
    fun `the fall back keeps a bitrate the rider has asked lower, and never raises it`() {
        val adaptive = VideoLoop.Settings().withRider(REauSettings(videoPaceFps = 12, experimentAdaptiveBitrate = true).sanitized(true))
        assertEquals(200_000, adaptive.bitrateBps)
        assertEquals(200_000, adaptive.atFullFrame().bitrateBps)
        assertEquals(DashConfig.streamProfile(8).frame, DashConfig.FULL_FRAME)
        assertEquals(8, DashConfig.FULL_FRAME_FALLBACK_FPS)
    }

    @Test
    fun `a stream that is already at the full frame has nothing to fall back to`() {
        for (pace in listOf(4, 6, 8)) {
            val s = VideoLoop.Settings().withRider(at(pace))
            assertSame("pace $pace", s, s.atFullFrame())
        }
        val back = VideoLoop.Settings().withRider(at(12)).atFullFrame()
        assertSame("and the fall back is done once", back, back.atFullFrame())
    }

    @Test
    fun `the video loop tries the full frame once, after the encoders failed, and says so in the log`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec) ?: return startAtFullFrame(rec)"))
        val fall = loop.substringAfter("private fun startAtFullFrame(").substringBefore("fun restartStream(")
        assertTrue("nothing to try at the full frame already", fall.contains("if (fallback == requested) return false"))
        assertTrue("the next start takes the settings of the fall back", fall.indexOf("settings = fallback") in 0 until fall.indexOf("return start()"))
        assertTrue("it says what happened, as a warning", fall.contains("\${RideDiagnostics.WARN}no encoder took the \${requested.frame.width}x\${requested.frame.height} picture \""))
        assertTrue(fall.contains("back to \${fallback.frame.width}x\${fallback.frame.height} at \${fallback.ceilingFps} fps"))
        assertTrue("the recorder of the failed try is closed", fall.contains("recorder?.close()"))
    }

    @Test
    fun `the settings screen tells the smaller picture next to the 12 fps pace and only there`() {
        val rows = source("ui/SettingsVideo.kt").substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        assertTrue(rows.contains("sub = if (s.frameSize == DashConfig.FULL_FRAME) {\n                stringResource(R.string.pace_sub, "))
        assertTrue(rows.contains("stringResource(R.string.pace_small_sub, s.fpsCeiling, s.profileReducedFps, DashConfig.RESERVE_CAP_FPS, s.frameSize.width, s.frameSize.height)"))
    }

    @Test
    fun `the start line of the video loop says the picture`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("\"picture \${settings.frame.width}x\${settings.frame.height}, keyframe \${g.keyframeSec}s fixed\""))
    }

    // ---- the encoder, the output and the loop know the size

    @Test
    fun `every encoder of a stream is built for the stream's picture and its surface is drawn at that size`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("created = DashEncoder(settings.frame) {"))
        assertTrue("the first output", loop.contains("compositor.setOutput(checkNotNull(fresh.inputSurface), settings.frame, onFrameSubmitted = fresh::frameSubmitted)"))
        assertTrue("a rebuilt encoder's", loop.contains("compositor.swapOutputThen(checkNotNull(fresh.inputSurface), settings.frame, onFrameSubmitted = fresh::frameSubmitted,"))
        assertTrue("the first start gets the picture of the rider", loop.contains("frame = rider.frameSize,"))
        assertEquals("the picture is set where the stream is made and nowhere else", 1, Regex("""settings = fallback""").findAll(loop).count())
    }

    @Test
    fun `the encoder asks the codec for its picture, in the format, in the list of the encoders that take it and in the line of the log`() {
        val encoder = source("dash/video/DashEncoder.kt")
        assertTrue(encoder.contains("MediaFormat.createVideoFormat(MIME, ask.frame.width, ask.frame.height)"))
        assertTrue(encoder.contains("Ask(fpsHint, bitrateBps, iFrameIntervalSec, colorMatrix, vendorLowLatency, frame)"))
        assertTrue("an encoder that does not take the size is left out of the order", encoder.contains("isSizeSupported(frame.width, frame.height)"))
        assertTrue("and the list kept for the process is of this size", encoder.contains("CodecNames.pick(\"dash encoders \${frame.width}x\${frame.height} baseline\")"))
        assertTrue(encoder.contains("val detail = \"size=\${frame.width}x\${frame.height} fpsHint="))
        assertTrue("a plain encoder is the full frame, as it was", encoder.contains("private val frame: DashConfig.FrameSize = DashConfig.FULL_FRAME,"))
        assertFalse("no size is fixed in the encoder any more", Regex("""const val (WIDTH|HEIGHT)""").containsMatchIn(encoder))
    }

    @Test
    fun `the compositor gives the output its size and the OpenGL side shrinks everything it draws for the dash to it, nothing else`() {
        val compositor = source("video/DashCompositor.kt")
        assertTrue(compositor.contains("gl.attachOutput(encoderSurface, size)"))
        assertTrue(compositor.contains("fun setOutput(encoderSurface: Surface, size: DashConfig.FrameSize = DashConfig.FULL_FRAME, onFrameSubmitted: (Long) -> Unit = {}) ="))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after = {})"))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after)"))
        assertTrue("the log line says the output", compositor.contains("output \${size.width}x\${size.height}"))
        val gl = source("video/CompositorGl.kt")
        assertTrue(gl.contains("outputW = size.width\n        outputH = size.height"))
        assertTrue("the picture", gl.contains("drawQuad(window, toOutput(viewport))"))
        assertTrue("the rider's cover", gl.contains("val out = toOutput(r)\n            GLES20.glScissor(out.x, out.y, out.w, out.h)"))
        assertTrue("the blurred background", gl.contains("val frame = toOutput(plan.frame)\n        GLES20.glViewport(frame.x, frame.y, frame.w, frame.h)"))
        assertEquals("the overlays, the ring, the legend and the clear cover the whole output", 2, Regex("""glViewport\(0, 0, outputW, outputH\)""").findAll(gl).count())
        assertFalse("no draw for the dash is left at the frame's own size", gl.contains("glViewport(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)"))
        assertTrue("the small copy of the blurred background and the preview keep their own sizes", gl.contains("drawQuad(plan.window, plan.small, LOOK_FILTER)"))
    }

    // ---- the arithmetic

    @Test
    fun `on an output of the frame's own size nothing moves`() {
        for (r in listOf(RectPx(0, 0, 526, 300), RectPx(13, 0, 500, 300), RectPx(0, 12, 526, 276), RectPx(7, 3, 1, 1), RectPx(100, 100, 0, 0))) {
            assertEquals(r, CompositorMath.toOutput(r, DashGlass.FRAME_W, DashGlass.FRAME_H))
        }
    }

    @Test
    fun `the whole frame is the whole small output`() {
        assertEquals(RectPx(0, 0, 368, 208), CompositorMath.toOutput(RectPx(0, 0, 526, 300), 368, 208))
    }

    @Test
    fun `each edge is scaled and rounded on its own side, x by the width and y by the height`() {
        // 13 x 368 / 526 = 9.09 and 513 x 368 / 526 = 358.9: 9 and 359
        assertEquals(RectPx(9, 0, 350, 208), CompositorMath.toOutput(RectPx(13, 0, 500, 300), 368, 208))
        // 12 x 208 / 300 = 8.3 and 288 x 208 / 300 = 199.7: a band of the top 12 rows is rows 200 to 208 of the small one
        assertEquals(RectPx(0, 200, 368, 8), CompositorMath.toOutput(RectPx(0, 288, 526, 12), 368, 208))
        assertEquals(RectPx(0, 0, 368, 8), CompositorMath.toOutput(RectPx(0, 0, 526, 12), 368, 208))
    }

    @Test
    fun `rectangles that meet in the frame meet in the output, and stay inside it`() {
        for (split in 0..DashGlass.FRAME_W) {
            val left = CompositorMath.toOutput(RectPx(0, 0, split, 300), 368, 208)
            val right = CompositorMath.toOutput(RectPx(split, 0, DashGlass.FRAME_W - split, 300), 368, 208)
            assertEquals("split at $split", left.x + left.w, right.x)
            assertTrue("split at $split", left.w >= 0 && right.w >= 0 && right.x + right.w == 368)
        }
        for (split in 0..DashGlass.FRAME_H) {
            val low = CompositorMath.toOutput(RectPx(0, 0, 526, split), 368, 208)
            val high = CompositorMath.toOutput(RectPx(0, split, 526, DashGlass.FRAME_H - split), 368, 208)
            assertEquals("split at $split", low.y + low.h, high.y)
            assertTrue("split at $split", high.y + high.h == 208)
        }
    }

    @Test
    fun `the arithmetic is not the other output`() {
        assertNotEquals(CompositorMath.toOutput(RectPx(0, 0, 526, 300), 368, 208), CompositorMath.toOutput(RectPx(0, 0, 526, 300), 526, 300))
    }

    // ---- the live tuning

    @Test
    fun `the picture is part of what a stream is made with`() {
        val tuning = source("service/LiveTuning.kt")
        assertTrue(tuning.contains("fpsCeiling, frameSize, clockSlowPercent,"))
    }

    @Test
    fun `the dash session's start, not the stream's, bounds the reading of the reserve in a loop made by the service`() {
        val loop = source("stream/VideoLoop.kt")
        assertFalse("no start of the stream of its own is kept", loop.contains("streamStartedAtMs"))
        assertTrue(loop.contains("private val sessionStartedAtMs: Long = ReserveCap.nowMs(),"))
    }
}
