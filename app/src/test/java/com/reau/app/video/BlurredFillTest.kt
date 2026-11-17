package com.reau.app.video

import com.reau.app.service.LiveTuning
import com.reau.app.settings.REauSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blurred background in the border the fit leaves ([CompositorMath.backdrop]): what is decided away from OpenGL. With the setting off, or a picture that
 * covers the whole frame, there is no plan and so no extra pass and no framebuffer; with a border, the plan says the size of the small copy and the rectangles.
 * The drawing itself is only seen on the dash.
 */
class BlurredFillTest {
    private val content = RectPx(0, 0, 800, 480)
    private val whole = FrameFit.wholeVisible(content)
    private val frame = RectPx(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)

    private fun plan(enabled: Boolean, mapping: FrameFit.Mapping) = CompositorMath.backdrop(enabled, mapping.source, mapping.dest, 800, 480)

    @Test
    fun `with the setting off nothing changes, border or not`() {
        assertNull(plan(false, whole))
        assertNull(plan(false, FrameFit.centerCrop(content, 64)))
        assertNull(plan(false, FrameFit.zoomed(FrameFit.centerCrop(content, 64), 60)))
    }

    @Test
    fun `with the setting on and no border there is no pass`() {
        // A crop fills the dash's whole frame, and so does a picture drawn pixel for pixel (the card).
        assertNull(plan(true, FrameFit.centerCrop(content, 64)))
        assertNull(plan(true, FrameFit.centerCrop(content, 90, 0, 100)))
        assertNull(plan(true, FrameFit.Mapping(frame, frame)))
    }

    @Test
    fun `with the setting on and a border the plan has the small copy and the whole frame`() {
        val p = plan(true, whole)!!
        assertEquals("a quarter of the frame, rounded up", RectPx(0, 0, 132, 75), p.small)
        assertEquals(RectPx(0, 0, 526, 300), p.frame)
        assertEquals(0.92f, p.keep, 0f)
        // the part of the picture the copy is made from: 800 x 456 in the middle of 800 x 480
        val w = CompositorMath.texWindow(RectPx(0, 12, 800, 456), 800, 480)
        assertEquals(w, p.window)
    }

    @Test
    fun `every fit that does not fill the frame has a border, and a zoomed out crop too`() {
        assertNotNull(plan(true, whole))
        assertNotNull(plan(true, FrameFit.fillDome(content)))
        assertNotNull(plan(true, FrameFit.zoomed(FrameFit.centerCrop(content, 64), 60)))
    }

    @Test
    fun `a gap on any one side is a border`() {
        val full = frame
        assertFalse(CompositorMath.leavesBorder(full))
        assertTrue("left", CompositorMath.leavesBorder(RectPx(1, 0, 525, 300)))
        assertTrue("top", CompositorMath.leavesBorder(RectPx(0, 1, 526, 299)))
        assertTrue("right", CompositorMath.leavesBorder(RectPx(0, 0, 525, 300)))
        assertTrue("bottom", CompositorMath.leavesBorder(RectPx(0, 0, 526, 299)))
    }

    @Test
    fun `the part the background is made from is the largest of the frame's shape, in the middle`() {
        assertEquals("a little taller than the frame's shape: the top and bottom go", RectPx(0, 12, 800, 456), CompositorMath.backdropSource(content))
        assertEquals("with margins", RectPx(50, 40, 700, 399), CompositorMath.backdropSource(RectPx(50, 0, 700, 480)))
        assertEquals("wider than the frame's shape: the sides go", RectPx(237, 0, 526, 300), CompositorMath.backdropSource(RectPx(0, 0, 1000, 300)))
        assertEquals("already the frame's shape", frame, CompositorMath.backdropSource(frame))
    }

    @Test
    fun `the part is always inside the source and has the frame's shape to a pixel`() {
        val aspect = DashGlass.FRAME_W.toDouble() / DashGlass.FRAME_H
        for ((w, h) in listOf(800 to 480, 700 to 480, 640 to 480, 1000 to 300, 526 to 300, 100 to 100, 300 to 1000, 17 to 16)) {
            val source = RectPx(10, 20, w, h)
            val part = CompositorMath.backdropSource(source)
            assertTrue("inside $source: $part", part.x >= source.x && part.y >= source.y && part.right <= source.right && part.bottom <= source.bottom)
            assertTrue("a side is whole: $part", part.w == source.w || part.h == source.h)
            assertEquals("shape of $source", aspect, part.w.toDouble() / part.h, aspect * 0.02 + 1.0 / part.h)
        }
    }

    @Test
    fun `a source slid past the picture's edge still makes a plan`() {
        val slid = FrameFit.centerCrop(content, 90, -50, 50).copy(dest = RectPx(78, 78, 370, 222))
        assertTrue(slid.pastEdge)
        assertNotNull(plan(true, slid))
    }

    @Test
    fun `without a picture size there is no plan`() {
        assertNull(CompositorMath.backdrop(true, whole.source, whole.dest, 0, 0))
    }

    @Test
    fun `the switch of the settings is taken live and by itself`() {
        val before = REauSettings()
        assertFalse("off by default", before.blurredFill)
        assertEquals(setOf(LiveTuning.Step.BLURRED_FILL), LiveTuning.steps(before, before.copy(blurredFill = true)))
        assertEquals(setOf(LiveTuning.Step.BLURRED_FILL), LiveTuning.steps(before.copy(blurredFill = true), before))
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before.copy(blurredFill = true), before.copy(blurredFill = true)))
    }
}
