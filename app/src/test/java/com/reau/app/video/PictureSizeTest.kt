package com.reau.app.video

import com.reau.app.dash.DashConfig
import com.reau.app.settings.REauSettings
import com.reau.app.stream.VideoLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picture size of the stream (plan 32): fixed at 528x304 in a debug and in a release build, composed directly in that area, 1:1, the fall back to 526x300
 * when no encoder takes 528x304, and the lines that wire all of it (the encoder,
 * the OpenGL output and the video loop need Android, so their wiring is read from the source, as the `*IsWiredTest` tests do).
 */
class PictureSizeTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val fixed = DashConfig.VIDEO_FRAME

    // ---- the size by choice

    @Test
    fun `the compositor's frame is the dash's 526x300, and the encoder is built for 528x304 in every build`() {
        assertEquals(listOf(526, 300), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals("the compositor's frame is the encoder's", listOf(DashGlass.FRAME_W, DashGlass.FRAME_H), listOf(DashConfig.FULL_FRAME.width, DashConfig.FULL_FRAME.height))
        assertEquals(DashConfig.VIDEO_FRAME, REauSettings().sanitized(debugBuild = false).frameSize)
        assertEquals("a stream made without the rider's settings (the tests) is the dash's own frame", DashConfig.FULL_FRAME, VideoLoop.Settings().frame)
    }

    @Test
    fun `the fixed picture is of nearly the shape of the frame, so the dash's stretching hardly changes it`() {
        val shape = DashConfig.FULL_FRAME.width.toDouble() / DashConfig.FULL_FRAME.height
        assertEquals(shape, fixed.width.toDouble() / fixed.height, FrameFit.MAX_DISTORTION * shape)
    }

    @Test
    fun `the picture is 528x304 in a debug and in a release build whatever was stored, in the settings and in the video loop`() {
        val loop = VideoLoop.Settings()
        for (stored in listOf("528x304", "448x256", "512x288", "526x300", "")) {
            for (debugBuild in listOf(true, false)) {
                val sanitized = REauSettings(videoFrame = stored).sanitized(debugBuild = debugBuild)
                assertEquals("$stored, debugBuild=$debugBuild", fixed, sanitized.frameSize)
                assertEquals("$stored, debugBuild=$debugBuild", fixed, loop.withRider(sanitized).frame)
            }
        }
    }

    // ---- the fall back when no encoder takes the chosen picture

    @Test
    fun `without an encoder for the picture the stream keeps its pace and its ceiling at 526x300`() {
        val chosen = VideoLoop.Settings().withRider(REauSettings(fpsCeiling = 8, streamBitrateBps = 700_000).sanitized(true))
        assertEquals(fixed, chosen.frame)
        val back = chosen.atFullFrame()
        assertEquals("the fixed 6 fps and 400 kbps", listOf(6, 400_000, DashConfig.FULL_FRAME), listOf(back.ceilingFps, back.bitrateBps, back.frame))
        assertEquals("only the picture moved", chosen.copy(frame = DashConfig.FULL_FRAME), back)
    }

    @Test
    fun `the fall back keeps the bitrate`() {
        val kept = VideoLoop.Settings().withRider(REauSettings().sanitized(true))
        assertEquals(400_000, kept.bitrateBps)
        val back = kept.atFullFrame()
        assertEquals(400_000, back.bitrateBps)
    }

    @Test
    fun `a stream that is already at 526x300 has nothing to fall back to`() {
        val plain = VideoLoop.Settings()
        assertSame(plain, plain.atFullFrame())
        val back = VideoLoop.Settings().withRider(REauSettings().sanitized(false)).atFullFrame()
        assertSame("and the fall back is done once", back, back.atFullFrame())
    }

    @Test
    fun `the fall back line is a warning that says the picture, the pace and the ceiling that are kept`() {
        val line = VideoLoop.fallbackLine(fixed, 8, 700_000) // any pace and ceiling: the line says what it is given
        assertTrue(line, line.startsWith(com.reau.app.data.RideDiagnostics.WARN))
        assertTrue(line, "528x304" in line && "526x300" in line && "8 fps" in line && "700 kbps" in line)
        assertTrue("it says what happened: $line", "no encoder took the 528x304 picture" in line)
    }

    @Test
    fun `the video loop tries 526x300 once, after the encoders failed, and says so in the log`() {
        val loop = source("stream/VideoLoop.kt")
        assertTrue(loop.contains("buildEncoder(out, g.encoderFps, g.activeBitrateBps, g.encoderKeyframeSec) ?: return startAtFullFrame(rec)"))
        val fall = loop.substringAfter("private fun startAtFullFrame(").substringBefore("fun restartStream(")
        assertTrue("nothing to try at the full frame already", fall.contains("if (fallback == requested) return false"))
        assertTrue("the next start takes the settings of the fall back", fall.indexOf("settings = fallback") in 0 until fall.indexOf("return start()"))
        assertTrue("it says what happened", fall.contains("log(fallbackLine(requested.frame, requested.ceilingFps, requested.bitrateBps))"))
        assertTrue("the recorder of the failed try is closed, said as cut short", fall.contains("recorder?.close(early = true)"))
        assertTrue(loop.contains("fun atFullFrame(): Settings = if (frame == DashConfig.FULL_FRAME) this else copy(frame = DashConfig.FULL_FRAME)"))
    }

    @Test
    fun `the normal stop of the loop closes the recording as the end of the session, not as cut short`() {
        val stop = source("stream/VideoLoop.kt").substringAfter("fun stop() {").substringBefore("log(\"video loop stop")
        assertTrue(stop, "        recorder?.close()\n        recorder = null" in stop)
    }

    // ---- the start line

    @Test
    fun `the start line says the pace, the ceiling, the picture and the load in MB a second`() {
        assertEquals("fixed video 6 fps / 400 kbps cap / 528x304 (load 3762 MB/s)", VideoLoop.videoChoiceText(6, 400_000, fixed))
        assertEquals("the fall back is 526x300, the same load", "fixed video 6 fps / 400 kbps cap / 526x300 (load 3762 MB/s)", VideoLoop.videoChoiceText(6, 400_000, DashConfig.FULL_FRAME))
        assertEquals("a hot phone's 4 fps", "fixed video 4 fps / 400 kbps cap / 528x304 (load 2508 MB/s)", VideoLoop.videoChoiceText(4, 400_000, fixed))
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
        assertTrue(encoder.contains("Ask(fpsHint, bitrateBps, iFrameIntervalSec, frame)"))
        assertTrue("an encoder that does not take the size is left out of the order", encoder.contains("isSizeSupported(frame.width, frame.height)"))
        assertTrue("and the list kept for the process is of this size", encoder.contains("CodecNames.pick(\"dash encoders \${frame.width}x\${frame.height} baseline\")"))
        assertTrue(encoder.contains("val detail = \"size=\${frame.width}x\${frame.height} fpsHint="))
        assertTrue("a plain encoder is the full frame, as it was", encoder.contains("private val frame: DashConfig.FrameSize = DashConfig.FULL_FRAME,"))
        assertFalse("no size is fixed in the encoder any more", Regex("""const val (WIDTH|HEIGHT)""").containsMatchIn(encoder))
    }

    @Test
    fun `the compositor gives the output its size and the OpenGL side draws everything for the dash in it, 1 to 1`() {
        val compositor = source("video/DashCompositor.kt")
        assertTrue(compositor.contains("gl.attachOutput(encoderSurface, size)"))
        assertTrue(compositor.contains("fun setOutput(encoderSurface: Surface, size: DashConfig.FrameSize = DashConfig.FULL_FRAME, onFrameSubmitted: (Long) -> Unit = {}) ="))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after = {})"))
        assertTrue(compositor.contains("switchOutput(encoderSurface, size, onFrameSubmitted, after)"))
        assertTrue("the log line says the output", compositor.contains("output \${size.width}x\${size.height} (1:1)"))
        val gl = source("video/CompositorGl.kt")
        assertTrue(gl.contains("outputW = size.width\n        outputH = size.height"))
        assertTrue("the picture, in its own viewport", gl.contains("drawQuad(window, viewport)"))
        assertTrue("the rider's cover, in the output's pixels", gl.contains("GLES20.glScissor(r.x, r.y, r.w, r.h)"))
        assertTrue("the overlays, the ring, the legend: the output's size", gl.contains("CompositorMath.overlayQuad(RectPx(x, y, width, height), outputW, outputH)"))
        assertEquals("the overlays and the clear cover the whole output", 2, Regex("""glViewport\(0, 0, outputW, outputH\)""").findAll(gl).count())
        assertFalse("no draw for the dash is left at the frame's own size", gl.contains("glViewport(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)"))
        assertTrue("the small copy of the look keeps its own size", gl.contains("drawQuad(WHOLE_PICTURE, RectPx(0, 0, PictureLook.SAMPLE_W, PictureLook.SAMPLE_H), LOOK_FILTER)"))
    }

    // ---- the area: the picture fills it, the elements keep their pixels, nothing is scaled

    private val content = RectPx(0, 0, 800, 480)

    @Test
    fun `the default area of the fit functions is the dash's 526x300, as it always was`() {
        val full = DashConfig.FULL_FRAME
        assertEquals(FrameFit.wholeVisible(content), FrameFit.wholeVisible(content, full))
        assertEquals(FrameFit.fillDome(content), FrameFit.fillDome(content, full))
        assertEquals(FrameFit.cropAt(content, 80, null, null), FrameFit.cropAt(content, 80, null, null, full))
        assertEquals(FrameFit.cropSize(content, 80), FrameFit.cropSize(content, 80, full))
        assertEquals(FrameFit.cropRange(content, 80), FrameFit.cropRange(content, 80, full))
        assertEquals(RectPx(78, 78, 370, 222), FrameFit.wholeVisible(content, full).dest)
        assertEquals(RectPx(13, 0, 500, 300), FrameFit.fillDome(content, full).dest)
        assertEquals(658 to 375, FrameFit.cropSize(content, 80, full))
    }

    @Test
    fun `in 528x304 the picture fills the area by the same rules`() {
        assertEquals("filling the dome: as large as fits, resting on the bottom edge, centred", RectPx(11, 0, 507, 304), FrameFit.fillDome(content, fixed).dest)
        assertEquals("whole and visible: the corners on the glass, resting on the area's bottom edge", RectPx(76, 80, 374, 224), FrameFit.wholeVisible(content, fixed).dest)
        assertEquals(304, FrameFit.wholeVisible(content, fixed).dest.bottom)
        // rows 300 to 303 are past the rows the glass shows (DashGlass.USABLE_BOTTOM), so a few pixels of the picture's bottom are not counted as seen
        assertTrue(DashGlass.visibleFraction(FrameFit.wholeVisible(content, fixed).dest) > 0.97)
        assertEquals("a crop of 100% is the whole area", 528 to 304, FrameFit.cropSize(content, 100, fixed))
        assertEquals(660 to 380, FrameFit.cropSize(content, 80, fixed))
        assertEquals(800 to 461, FrameFit.cropSize(content, 66, fixed))
        val crop = FrameFit.cropAt(content, 100, 100, 50, fixed)
        assertEquals("the crop's window is shown over the whole area", RectPx(0, 0, 528, 304), crop.dest)
        assertEquals(RectPx(100, 50, 528, 304), crop.source)
    }

    @Test
    fun `a move stops at the edges of the area, so the picture can reach the extra columns and rows`() {
        val whole = FrameFit.fillDome(content, fixed)
        val right = FrameFit.moved(whole, 100, 100, fixed)
        assertEquals("527 - 507 + 1... the far edge of 528", 528, right.dest.right)
        assertEquals(0, right.dest.y)
        val left = FrameFit.moved(whole, -100, -100, fixed)
        assertEquals(0, left.dest.x)
        val small = FrameFit.wholeVisible(content, fixed)
        assertEquals(304, FrameFit.moved(small, 0, 100, fixed).dest.bottom)
        assertEquals("in the dash's own frame the same move stops at 300", 300, FrameFit.moved(FrameFit.wholeVisible(content), 0, 100).dest.bottom)
    }

    @Test
    fun `a fit made for 528x304 is only valid in an area that holds it, and the extra pixels count as the frame`() {
        val whole = FrameFit.fillDome(content, fixed)
        assertEquals(emptyList<FrameFit.Problem>(), FrameFit.problems(whole, 800, 480, fixed))
        assertEquals(listOf(FrameFit.Problem.DEST_OUTSIDE_FRAME), FrameFit.problems(whole, 800, 480))
        assertEquals(emptyList<FrameFit.Problem>(), FrameFit.problems(FrameFit.fillDome(content), 800, 480))
        assertEquals(emptyList<FrameFit.Problem>(), FrameFit.problems(FrameFit.fillDome(content), 800, 480, fixed))
    }

    @Test
    fun `the settings fit the picture for their own area, 528x304 in a debug and in a release build, and 526x300 after the fall back`() {
        val debug = REauSettings(fit = com.reau.app.settings.Fit.CROP, cropPercent = 100, cropX = null, cropY = null, moveX = 0, moveY = 0).sanitized(debugBuild = true)
        val release = debug.sanitized(debugBuild = false)
        assertEquals(RectPx(0, 0, 528, 304), debug.mapping.dest)
        assertEquals(RectPx(0, 0, 528, 304), release.mapping.dest)
        assertEquals(fixed, debug.frameSize)
        assertEquals(fixed, release.frameSize)
        val fallen = release.copy(videoFrame = DashConfig.FULL_FRAME.id)
        assertEquals(RectPx(0, 0, 526, 300), fallen.mapping.dest)
        assertEquals(DashConfig.FULL_FRAME, fallen.frameSize)
        val whole = REauSettings(fit = com.reau.app.settings.Fit.WHOLE_VISIBLE, moveX = 0, moveY = 0)
        assertEquals(FrameFit.wholeVisible(whole.content, fixed).dest, whole.sanitized(true).mapping.dest)
        assertEquals(FrameFit.wholeVisible(whole.content, fixed).dest, whole.sanitized(false).mapping.dest)
        assertEquals(FrameFit.wholeVisible(whole.content).dest, whole.sanitized(false).copy(videoFrame = DashConfig.FULL_FRAME.id).mapping.dest)
        assertEquals("the saved crop place is kept within the range of the area", 0, REauSettings(cropX = 0).sanitized(true).cropX)
    }

    @Test
    fun `the black bands of the rider are those of the area, so the right and bottom ones reach its edges`() {
        val cover = FrameFit.Cover(top = 10, bottom = 20, left = 30, right = 40)
        assertEquals(
            listOf(RectPx(0, 294, 528, 10), RectPx(0, 0, 528, 20), RectPx(0, 0, 30, 304), RectPx(488, 0, 40, 304)),
            CompositorMath.coverRects(cover, 528, 304),
        )
        assertEquals(CompositorMath.coverRects(cover), CompositorMath.coverRects(cover, 526, 300))
        assertEquals(listOf(RectPx(486, 0, 40, 300)), CompositorMath.coverRects(FrameFit.Cover(right = 40)))
    }

    @Test
    fun `the picture's rectangle becomes the GL viewport of the area by its rows from the bottom, 1 to 1`() {
        assertEquals(RectPx(11, 0, 507, 304), CompositorMath.viewport(RectPx(11, 0, 507, 304), 304))
        assertEquals(RectPx(76, 0, 374, 224), CompositorMath.viewport(RectPx(76, 80, 374, 224), 304))
        assertEquals(RectPx(78, 0, 370, 222), CompositorMath.viewport(RectPx(78, 78, 370, 222), 300))
        assertEquals("the default is the dash's frame", CompositorMath.viewport(RectPx(78, 78, 370, 222), 300), CompositorMath.viewport(RectPx(78, 78, 370, 222)))
    }

    @Test
    fun `an overlay keeps its pixel from the top left in the 528x304 area as in the 526x300 frame, with nothing scaled`() {
        // 100 x 40 box at (20, 10): the same pixels, so only the share of the (larger) area they take changes
        val a = CompositorMath.overlayQuad(RectPx(20, 10, 100, 40), 528, 304)
        assertEquals(20f / 528 * 2 - 1, a.left, 1e-6f)
        assertEquals(120f / 528 * 2 - 1, a.right, 1e-6f)
        assertEquals(1f - 10f / 304 * 2, a.top, 1e-6f)
        assertEquals(1f - 50f / 304 * 2, a.bottom, 1e-6f)
        // the pixel size of the box is the area's half-width times the span: 1:1
        assertEquals(100f, (a.right - a.left) / 2 * 528, 1e-3f)
        assertEquals(40f, (a.top - a.bottom) / 2 * 304, 1e-3f)
        // the whole area is -1..1
        val whole = CompositorMath.overlayQuad(RectPx(0, 0, 528, 304), 528, 304)
        assertEquals(listOf(-1f, 1f, 1f, -1f), listOf(whole.left, whole.right, whole.top, whole.bottom))
        val frame = CompositorMath.overlayQuad(RectPx(0, 0, 526, 300), 526, 300)
        assertEquals(listOf(-1f, 1f, 1f, -1f), listOf(frame.left, frame.right, frame.top, frame.bottom))
    }

    @Test
    fun `the centred and right-anchored elements stay at the pixel they have in 526x300`() {
        // decided on the owner's word: the same pixels, so the 2 columns and 4 rows only extend the picture and the background
        assertEquals((526 - 100) / 2 to (300 - 40) / 2, LegendLayout.corner(100, 40))
        assertEquals((526 - 100) / 2 to (300 - 40 - 10), StaleBadgeInk.corner(100, 40))
        assertEquals(526 - 85, InfoRingSpot.CENTER_X)
    }

    @Test
    fun `the compositor composes in the area it is started with, and re-plans the bands for an output of another size`() {
        val compositor = source("video/DashCompositor.kt")
        assertTrue(compositor.contains("fun start(sourceW: Int, sourceH: Int, area: DashConfig.FrameSize = DashConfig.FULL_FRAME): Boolean {"))
        assertTrue(compositor.contains("val mapping = FrameFit.fillDome(RectPx(0, 0, sourceW, sourceH), area)"))
        assertTrue("start takes the area", compositor.contains("                this.area = area\n"))
        assertTrue("the mapping is checked against the area", compositor.contains("FrameFit.problems(mapping, sourceW, sourceH, area)"))
        assertTrue(compositor.contains("cover = CompositorMath.coverRects(mapping.cover, area.width, area.height)"))
        assertTrue(compositor.contains("private val viewport: RectPx get() = CompositorMath.viewport(dest, area.height)"))
        val switch = compositor.substringAfter("private fun switchOutput(").substringBefore("fun dropOutput")
        assertTrue(switch.contains("if (size != area) {\n                    area = size"))
        assertTrue(switch.contains("fitInUse?.let { cover = CompositorMath.coverRects(it.cover, size.width, size.height) }"))
        assertFalse("nothing is scaled any more", compositor.contains("toOutput") || source("video/CompositorGl.kt").contains("toOutput"))
    }

    @Test
    fun `the live sides start the compositor in the stream's area and follow a fall back with a fit made for 526x300`() {
        val sides = source("service/LiveSides.kt")
        assertTrue(sides.contains("compositor.start(com.reau.app.aa.AaVideo.WIDTH, com.reau.app.aa.AaVideo.HEIGHT, setup.video.frame)"))
        assertTrue("the card, at the top left of the area", sides.contains("compositor.start(DashGlass.FRAME_W, DashGlass.FRAME_H, setup.video.frame)"))
        assertTrue(sides.contains("private fun inStreamArea(settings: REauSettings): REauSettings = fallbackArea?.let { settings.copy(videoFrame = it.id) } ?: settings"))
        assertTrue(sides.contains("inStreamArea(settings).mappingFor(screen)"))
        assertTrue("the follower reads the settings of the stream's area", sides.contains("FitFollower({ tuned?.let(::inStreamArea) }, ::remap)"))
        assertTrue(sides.contains("if (fallback != fallbackArea) {\n                fallbackArea = fallback"))
        assertTrue(sides.contains("val fallback = used.takeIf { it != videoSettings.frame }"))
        assertTrue(sides.contains("followFallback(fresh.frame)"))
        assertTrue("the fit is made again", sides.contains("                    remap(fitOf(s))\n                }\n            }"))
        assertEquals("every fit but the one the sides start with comes from fitOf", 1, Regex("""settings\.mappingFor\(com\.reau""").findAll(sides).count())
        assertTrue("the video loop says the area it really runs in", source("stream/VideoLoop.kt").contains("val frame: DashConfig.FrameSize get() = settings.frame"))
    }

    @Test
    fun `the settings screens make the mask, the counters and the check of the fit for the settings' own area`() {
        for (file in listOf("PanelMask", "StatsOverlayPreview", "ButtonOverlayPreview")) {
            assertTrue(file, source("ui/components/$file.kt").contains("size.width.toInt(), size.height.toInt(), mask.area)"))
        }
        assertTrue(source("MainActivity.kt").contains("com.reau.app.aa.AaVideo.HEIGHT, settings.frameSize) {"))
        assertTrue(source("ui/FitPanel.kt").contains("FrameFit.problems(mapping, AaVideo.WIDTH, AaVideo.HEIGHT, s.frameSize)"))
        val mask = source("ui/AaScreenMask.kt")
        assertTrue("a mask made without an area is the dash's own frame", mask.contains("val area: com.reau.app.dash.DashConfig.FrameSize = com.reau.app.dash.DashConfig.FULL_FRAME,"))
    }

    @Test
    fun `the dash mask is drawn for the area of the fit`() {
        val mapping = FrameFit.fillDome(content, fixed)
        val in528 = checkNotNull(DashMask.of(mapping, 800, 480, 800, 480, fixed))
        // the frame box is the area, and the glass is the dash's, wherever the area ends
        val perDashPixel528 = in528.glass.r / DashGlass.RADIUS
        assertEquals(528 * perDashPixel528, in528.frame.right - in528.frame.left, 1e-2f)
        // the fit shows 480 rows of Android Auto in 304 rows of the dash: the frame box spans 304 rows of them
        assertEquals(304f * mapping.source.h / mapping.dest.h, in528.frame.bottom - in528.frame.top, 1e-2f)
    }

    // ---- the live tuning

    @Test
    fun `the picture is part of what a stream is made with`() {
        val tuning = source("service/LiveTuning.kt")
        assertTrue(tuning.contains("fpsCeiling, frameSize, clockSlowPercent,"))
    }
}
