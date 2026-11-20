package com.reau.app.video

import com.reau.app.dash.DashConfig
import com.reau.app.dash.DashConfig.FrameSize
import com.reau.app.dash.VideoChoice
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
 * The picture size of the stream (plan 32): the size of the encoder by choice, the arithmetic that scales the dash's 526x300 frame to it on the way out (larger
 * and smaller), the area filter for the smaller ones, the fall back to 526x300 when no encoder takes the chosen size, and the lines that wire all of it (the encoder,
 * the OpenGL output and the video loop need Android, so their wiring is read from the source, as the `*IsWiredTest` tests do).
 */
class PictureSizeTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun at(frame: FrameSize) = REauSettings(videoFrame = frame.id).sanitized(debugBuild = true)

    // ---- the size by choice

    @Test
    fun `the compositor's frame is the dash's 526x300, and the encoder is built for it in a release build`() {
        assertEquals(listOf(526, 300), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals("the compositor's frame is the encoder's", listOf(DashGlass.FRAME_W, DashGlass.FRAME_H), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals(DashConfig.FULL_FRAME, REauSettings().sanitized(debugBuild = false).frameSize)
        assertEquals(DashConfig.FULL_FRAME, VideoLoop.Settings().frame)
    }

    @Test
    fun `every offered picture is of nearly the shape of the frame, so the dash's stretching hardly changes it`() {
        val shape = DashConfig.FULL_FRAME.width.toDouble() / DashConfig.FULL_FRAME.height
        for (f in VideoChoice.FRAMES) assertEquals(f.id, shape, f.width.toDouble() / f.height, 0.08)
    }

    @Test
    fun `the picture follows the choice, in the settings and in the video loop`() {
        val loop = VideoLoop.Settings()
        for (f in VideoChoice.FRAMES) {
            assertEquals(f, at(f).frameSize)
            assertEquals(f, loop.withRider(at(f)).frame)
        }
        assertEquals("a release build is the full frame", DashConfig.FULL_FRAME, loop.withRider(REauSettings(videoFrame = "448x256").sanitized(debugBuild = false)).frame)
    }

    // ---- the fall back when no encoder takes the chosen picture

    @Test
    fun `without an encoder for the picture the stream keeps its pace and its ceiling at 526x300`() {
        for (f in VideoChoice.FRAMES) {
            val chosen = VideoLoop.Settings().withRider(REauSettings(fpsCeiling = 10, streamBitrateBps = 700_000, videoFrame = f.id).sanitized(true))
            val back = chosen.atFullFrame()
            assertEquals(listOf(10, 700_000, DashConfig.FULL_FRAME), listOf(back.ceilingFps, back.bitrateBps, back.frame))
            assertEquals("only the picture moved (${f.id})", chosen.copy(frame = DashConfig.FULL_FRAME), back)
        }
    }

    @Test
    fun `the fall back keeps the lower bitrate of the experiment and the switch of the reserve`() {
        val adaptive = VideoLoop.Settings().withRider(REauSettings(videoFrame = "448x256", experimentAdaptiveBitrate = true, reserveCapOn = false).sanitized(true))
        assertEquals(200_000, adaptive.bitrateBps)
        val back = adaptive.atFullFrame()
        assertEquals(200_000, back.bitrateBps)
        assertFalse(back.reserveCap)
    }

    @Test
    fun `a stream that is already at 526x300 has nothing to fall back to`() {
        val release = VideoLoop.Settings().withRider(REauSettings().sanitized(false))
        assertSame(release, release.atFullFrame())
        val back = VideoLoop.Settings().withRider(at(FrameSize(448, 256))).atFullFrame()
        assertSame("and the fall back is done once", back, back.atFullFrame())
    }

    @Test
    fun `the fall back line is a warning that says the picture, the pace and the ceiling that are kept`() {
        val line = VideoLoop.fallbackLine(FrameSize(480, 272), 10, 700_000)
        assertTrue(line, line.startsWith(com.reau.app.data.RideDiagnostics.WARN))
        assertTrue(line, "480x272" in line && "526x300" in line && "10 fps" in line && "700 kbps" in line)
    }

    @Test
    fun `the video loop tries 526x300 once, after the encoders failed, and says so in the log`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec) ?: return startAtFullFrame(rec)"))
        val fall = loop.substringAfter("private fun startAtFullFrame(").substringBefore("fun restartStream(")
        assertTrue("nothing to try at the full frame already", fall.contains("if (fallback == requested) return false"))
        assertTrue("the next start takes the settings of the fall back", fall.indexOf("settings = fallback") in 0 until fall.indexOf("return start()"))
        assertTrue("it says what happened", fall.contains("log(fallbackLine(requested.frame, requested.ceilingFps, requested.bitrateBps))"))
        assertTrue("the recorder of the failed try is closed", fall.contains("recorder?.close()"))
        assertTrue(loop.contains("fun atFullFrame(): Settings = if (frame == DashConfig.FULL_FRAME) this else copy(frame = DashConfig.FULL_FRAME)"))
    }

    // ---- the start line

    @Test
    fun `the start line says the pace, the ceiling, the picture, the guide and the load, with a note when it is heavy`() {
        val plain = VideoLoop.videoChoiceText(6, 400_000, FrameSize(528, 304))
        assertEquals("choice 6 fps / 400 kbps cap / 528x304 (recommended 400 kbps, load 3762 MB/s)", plain)
        val heavy = VideoLoop.videoChoiceText(8, 400_000, FrameSize(528, 304))
        assertEquals("choice 8 fps / 400 kbps cap / 528x304 (recommended 600 kbps, load 5016 MB/s, above the 3762 MB/s the dash showed it holds)", heavy)
        assertFalse("8 fps in 448x256 is under the line", "above" in VideoLoop.videoChoiceText(8, 600_000, FrameSize(448, 256)))
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("videoChoiceText(settings.ceilingFps, settings.bitrateBps, settings.frame)"))
    }

    // ---- the encoder, the output and the loop know the size

    @Test
    fun `every encoder of a stream is built for the stream's picture and its surface is drawn at that size`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("created = DashEncoder(settings.frame) {"))
        assertTrue("the first output", loop.contains("compositor.setOutput(checkNotNull(fresh.inputSurface), settings.frame, onFrameSubmitted = fresh::frameSubmitted)"))
        assertTrue("a rebuilt encoder's", loop.contains("compositor.swapOutputThen(checkNotNull(fresh.inputSurface), settings.frame, onFrameSubmitted = fresh::frameSubmitted,"))
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
    fun `the compositor gives the output its size and the OpenGL side scales everything it draws for the dash to it, nothing else`() {
        val compositor = source("video/DashCompositor.kt")
        assertTrue(compositor.contains("gl.attachOutput(encoderSurface, size)"))
        assertTrue(compositor.contains("fun setOutput(encoderSurface: Surface, size: DashConfig.FrameSize = DashConfig.FULL_FRAME, onFrameSubmitted: (Long) -> Unit = {}) ="))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after = {})"))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after)"))
        assertTrue("the log line says the output", compositor.contains("output \${size.width}x\${size.height}"))
        val gl = source("video/CompositorGl.kt")
        assertTrue(gl.contains("outputW = size.width\n        outputH = size.height"))
        assertTrue("the picture", gl.contains("drawQuad(window, toOutput(viewport), SMALL_OUTPUT_FILTER.takeIf"))
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
    fun `the whole frame is the whole output, larger or smaller`() {
        for (f in VideoChoice.FRAMES) assertEquals(f.id, RectPx(0, 0, f.width, f.height), CompositorMath.toOutput(RectPx(0, 0, 526, 300), f.width, f.height))
        assertEquals(RectPx(0, 0, 528, 304), CompositorMath.toOutput(RectPx(0, 0, 526, 300), 528, 304))
    }

    @Test
    fun `each edge is scaled and rounded on its own side, x by the width and y by the height`() {
        // 13 x 448 / 526 = 11.07 and 513 x 448 / 526 = 436.9: 11 and 437
        assertEquals(RectPx(11, 0, 426, 256), CompositorMath.toOutput(RectPx(13, 0, 500, 300), 448, 256))
        // 12 x 256 / 300 = 10.2 and 288 x 256 / 300 = 245.8: a band of the top 12 rows is rows 246 to 256 of the smaller one
        assertEquals(RectPx(0, 246, 448, 10), CompositorMath.toOutput(RectPx(0, 288, 526, 12), 448, 256))
        assertEquals(RectPx(0, 0, 448, 10), CompositorMath.toOutput(RectPx(0, 0, 526, 12), 448, 256))
        // larger: 13 x 528 / 526 = 13.05 and 513 x 528 / 526 = 514.9: 13 and 515
        assertEquals(RectPx(13, 0, 502, 304), CompositorMath.toOutput(RectPx(13, 0, 500, 300), 528, 304))
    }

    @Test
    fun `rectangles that meet in the frame meet in the output, and stay inside it, for every offered picture`() {
        for (out in VideoChoice.FRAMES) {
            for (split in 0..DashGlass.FRAME_W) {
                val left = CompositorMath.toOutput(RectPx(0, 0, split, 300), out.width, out.height)
                val right = CompositorMath.toOutput(RectPx(split, 0, DashGlass.FRAME_W - split, 300), out.width, out.height)
                assertEquals("${out.id} split at $split", left.x + left.w, right.x)
                assertTrue("${out.id} split at $split", left.w >= 0 && right.w >= 0 && right.x + right.w == out.width)
            }
            for (split in 0..DashGlass.FRAME_H) {
                val low = CompositorMath.toOutput(RectPx(0, 0, 526, split), out.width, out.height)
                val high = CompositorMath.toOutput(RectPx(0, split, 526, DashGlass.FRAME_H - split), out.width, out.height)
                assertEquals("${out.id} split at $split", low.y + low.h, high.y)
                assertTrue("${out.id} split at $split", high.y + high.h == out.height)
            }
        }
    }

    @Test
    fun `the arithmetic is not the other output`() {
        assertNotEquals(CompositorMath.toOutput(RectPx(0, 0, 526, 300), 448, 256), CompositorMath.toOutput(RectPx(0, 0, 526, 300), 526, 300))
        assertNotEquals(CompositorMath.toOutput(RectPx(10, 10, 100, 100), 528, 304), CompositorMath.toOutput(RectPx(10, 10, 100, 100), 526, 300))
    }

    // ---- the area filter on the smaller outputs

    @Test
    fun `only an output smaller than the frame on a side takes the area filter, and 528x304 does not`() {
        assertFalse("the frame's own size is drawn as always", CompositorMath.needsAreaFilter(DashGlass.FRAME_W, DashGlass.FRAME_H))
        assertFalse("528x304 is larger", CompositorMath.needsAreaFilter(528, 304))
        assertTrue("one side is enough", CompositorMath.needsAreaFilter(DashGlass.FRAME_W, DashGlass.FRAME_H - 1))
        assertTrue("one side is enough", CompositorMath.needsAreaFilter(DashGlass.FRAME_W - 1, DashGlass.FRAME_H))
        assertFalse(CompositorMath.needsAreaFilter(DashGlass.FRAME_W + 16, DashGlass.FRAME_H + 16))
        for (f in VideoChoice.FRAMES) assertEquals(f.id, f != FrameSize(528, 304), CompositorMath.needsAreaFilter(f.width, f.height))
        assertEquals(listOf(true), listOf(CompositorMath.needsAreaFilter(448, 256)))
    }

    @Test
    fun `the picture of the dash is shrunk with the area average on a smaller output, and the plain fetch of the frame is left as it was`() {
        val gl = source("video/CompositorGl.kt")
        assertTrue(gl.contains("drawQuad(window, toOutput(viewport), SMALL_OUTPUT_FILTER.takeIf { CompositorMath.needsAreaFilter(outputW, outputH) })"))
        assertTrue("the filter is the box of 4x4 taps", gl.contains("private val SMALL_OUTPUT_FILTER = ReductionFilter(ReductionFilter.Kernel.BOX)"))
        // a null filter is the stream's own ([ReductionFilter.CURRENT]): the full frame's path does not change
        assertTrue(gl.contains("val variant = (filter ?: reductionFilter).takeUnless { it.isCurrent }"))
        assertTrue("and the default of the stream is still the plain one", gl.contains("var reductionFilter: ReductionFilter = ReductionFilter.CURRENT"))
        assertEquals("the area filter is asked for in one place only, besides the small copies", 1, Regex("""SMALL_OUTPUT_FILTER\.takeIf""").findAll(gl).count())
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
