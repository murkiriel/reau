package com.reau.app.ui

import android.content.res.Configuration
import com.reau.app.aa.PhoneTouch
import com.reau.app.video.CompositorMath
import com.reau.app.video.RectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full screen of the "Tela do Android Auto" on its side (28/10/2026): when it is on, where Android Auto's whole picture lands on a landscape
 * phone (as big as fits, centred, on black) and what a finger on it, or on the black, means for Android Auto (800×480).
 */
class AaFullScreenTest {
    // A phone on its side without its bars: 2400×1080 pixels.
    private val canvasW = 2400
    private val canvasH = 1080

    @Test
    fun `the screen is full only on its side, and leaving it with Back lasts until the phone is turned upright`() {
        assertTrue(AaFullScreen.isLandscape(Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(AaFullScreen.isLandscape(Configuration.ORIENTATION_PORTRAIT))
        assertTrue(AaFullScreen.on(landscape = true, dismissed = false))
        assertFalse(AaFullScreen.on(landscape = false, dismissed = false))
        assertFalse("left with Back", AaFullScreen.on(landscape = true, dismissed = true))
        assertTrue("still left while on its side", AaFullScreen.dismissedAfter(landscape = true, dismissed = true))
        assertFalse("forgotten upright, so the next turn enters again", AaFullScreen.dismissedAfter(landscape = false, dismissed = true))
        assertFalse(AaFullScreen.dismissedAfter(landscape = true, dismissed = false))
    }

    @Test
    fun `the whole picture lands centred and uncropped on a landscape phone, with black at the sides`() {
        val v = CompositorMath.previewViewport(800, 480, canvasW, canvasH)
        assertEquals(RectPx(300, 0, 1800, 1080), v)
        assertEquals("the picture's shape is kept", 800.0 / 480.0, v.w.toDouble() / v.h, 0.001)
        assertTrue("the whole of it fits", v.x >= 0 && v.y >= 0 && v.right <= canvasW && v.bottom <= canvasH)
        // A wider phone (a long screen): still the whole picture, centred.
        val wide = CompositorMath.previewViewport(800, 480, 2640, 1080)
        assertEquals(RectPx(420, 0, 1800, 1080), wide)
    }

    @Test
    fun `a finger on the picture goes to Android Auto where it is, and one on the black does not`() {
        val v = CompositorMath.previewViewport(800, 480, canvasW, canvasH)
        val centre = PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 1200f, 540f), v, 800, 480)
        assertEquals(400 to 240, centre)
        val corner = PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 300f, 0f), v, 800, 480)
        assertEquals("the picture's top left corner", 0 to 0, corner)
        val last = PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 2099f, 1079f), v, 800, 480)
        assertEquals("the bottom right pixel", 799 to 479, last)
        assertNull("the black on the left", PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 100f, 540f), v, 800, 480))
        assertNull("the black on the right", PhoneTouch.toVideo(PhoneTouch(PhoneTouch.Action.DOWN, 2200f, 540f), v, 800, 480))
        // A stroke that began on the picture goes on to its end, clamped, when the finger leaves it (PhoneTouch.Stroke).
        val stroke = PhoneTouch.Stroke()
        assertEquals(400 to 240, stroke.next(PhoneTouch(PhoneTouch.Action.DOWN, 1200f, 540f), v, 800, 480)?.let { it.x to it.y })
        assertEquals(799 to 240, stroke.next(PhoneTouch(PhoneTouch.Action.MOVE, 2350f, 540f), v, 800, 480)?.let { it.x to it.y })
    }
}
