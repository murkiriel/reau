package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map's zoom from the joystick: from 100 to 300 in steps of 25, and the picture it makes of a fit. */
class LiveZoomTest {
    private val whole800 = RectPx(0, 0, 800, 480)

    private fun mapping() = FrameFit.Mapping(source = RectPx(0, 0, 800, 480), dest = RectPx(100, 0, 326, 300))

    @Test
    fun `the zoom is for the navigation apps, the menu and the other apps keep the fit`() {
        val zoom = LiveZoom().apply { step(1); step(1) }
        val fit = mapping()
        assertEquals(zoom.apply(fit), zoom.applyFor(com.reau.app.aa.AaScreen.NAV, fit))
        assertTrue("the zoom changes the source", zoom.apply(fit) != fit)
        for (other in com.reau.app.aa.AaScreen.entries - com.reau.app.aa.AaScreen.NAV) {
            assertEquals("$other keeps the fit as it is", fit, zoom.applyFor(other, fit))
        }
        assertTrue(LiveZoom.appliesTo(com.reau.app.aa.AaScreen.NAV))
        assertFalse(LiveZoom.appliesTo(com.reau.app.aa.AaScreen.MENU))
        assertFalse(LiveZoom.appliesTo(com.reau.app.aa.AaScreen.APPS))
        assertFalse(LiveZoom.appliesTo(com.reau.app.aa.AaScreen.UNKNOWN))
    }

    @Test
    fun `it starts at 100 and leaves the fit as it is`() {
        val zoom = LiveZoom()
        assertEquals(100, zoom.percent)
        val fit = FrameFit.Mapping(RectPx(0, 0, 800, 480), RectPx(0, 0, 526, 300))
        assertEquals(fit, zoom.apply(fit))
    }

    @Test
    fun `each step is 25 and the ends hold`() {
        val zoom = LiveZoom()
        assertFalse("not farther than the fit", zoom.step(-1))
        assertEquals(100, zoom.percent)
        assertTrue(zoom.step(1))
        assertEquals(125, zoom.percent)
        repeat(20) { zoom.step(1) }
        assertEquals(300, zoom.percent)
        assertFalse(zoom.step(1))
        assertTrue(zoom.step(-1))
        assertEquals(275, zoom.percent)
    }

    @Test
    fun `the last step taken back puts the zoom where it was, once`() {
        val zoom = LiveZoom()
        zoom.step(1)
        zoom.step(1)
        assertTrue(zoom.undoLast())
        assertEquals(125, zoom.percent)
        assertFalse("only the last step, once", zoom.undoLast())
        assertEquals(125, zoom.percent)
    }

    @Test
    fun `a step that changed nothing has nothing to take back`() {
        val zoom = LiveZoom()
        assertFalse(zoom.step(-1))
        assertFalse(zoom.undoLast())
        assertEquals(100, zoom.percent)
        repeat(20) { zoom.step(1) }
        assertEquals(300, zoom.percent)
        assertFalse("at the end a step does nothing", zoom.step(1))
        assertFalse(zoom.undoLast())
        assertEquals(300, zoom.percent)
    }

    @Test
    fun `a step is one however large the direction`() {
        val zoom = LiveZoom()
        zoom.step(9)
        assertEquals(125, zoom.percent)
    }

    @Test
    fun `the zoomed fit is the fit nearer from its bottom edge`() {
        val zoom = LiveZoom()
        val fit = FrameFit.Mapping(RectPx(0, 0, 800, 480), RectPx(0, 0, 526, 300))
        repeat(4) { zoom.step(1) }
        assertEquals(LiveZoom.fromBottom(fit, 200), zoom.apply(fit))
        assertEquals(480, zoom.apply(fit).source.let { it.y + it.h })
    }

    @Test
    fun `the zoom from the bottom keeps the bottom edge and the middle sideways`() {
        val whole = FrameFit.wholeVisible(whole800)
        assertEquals(whole, LiveZoom.fromBottom(whole, 100))
        assertEquals("never farther", whole, LiveZoom.fromBottom(whole, 60))
        val near = LiveZoom.fromBottom(whole, 200)
        assertEquals(whole.dest, near.dest)
        assertEquals(RectPx(200, 240, 400, 240), near.source)
        assertEquals(whole.source.y + whole.source.h, near.source.y + near.source.h)
        assertEquals(emptyList<FrameFit.Problem>(), FrameFit.problems(near, 800, 480))
        val third = LiveZoom.fromBottom(whole, 300)
        assertEquals(whole.source.y + whole.source.h, third.source.y + third.source.h)
        assertEquals(whole.source.x + whole.source.w / 2, third.source.x + third.source.w / 2)
    }

    @Test
    fun `the zoom from the bottom keeps the cover and the shape`() {
        val whole = FrameFit.wholeVisible(whole800).copy(cover = FrameFit.Cover(top = 10, right = 20))
        for (z in 100..300 step 25) {
            val m = LiveZoom.fromBottom(whole, z)
            assertEquals("zoom $z", whole.cover, m.cover)
            assertEquals("zoom $z", emptyList<FrameFit.Problem>(), FrameFit.problems(m, 800, 480))
        }
    }

    private class Memory(override var percent: Int = 100, val failing: Boolean = false) : ZoomMemory

    @Test
    fun `a new projection starts from the zoom kept`() {
        assertEquals(175, LiveZoom(Memory(175)).percent)
        assertEquals(300, LiveZoom(Memory(300)).percent)
        assertEquals(100, LiveZoom(Memory(100)).percent)
        assertEquals("none kept", 100, LiveZoom().percent)
    }

    @Test
    fun `every step and every step taken back is kept`() {
        val memory = Memory()
        val zoom = LiveZoom(memory)
        zoom.step(1)
        assertEquals(125, memory.percent)
        zoom.step(1)
        assertEquals(150, memory.percent)
        zoom.undoLast()
        assertEquals(125, memory.percent)
        zoom.step(-1)
        assertEquals(100, memory.percent)
        assertEquals("what is kept starts the next one", 100, LiveZoom(memory).percent)
    }

    @Test
    fun `a step that changes nothing keeps nothing`() {
        val memory = Memory(percent = 300)
        val zoom = LiveZoom(memory)
        memory.percent = 250 // changed behind its back: a step that does nothing must not write over it
        assertFalse(zoom.step(1))
        assertEquals(250, memory.percent)
    }

    @Test
    fun `a zoom kept out of range or off the steps is taken to the nearest step`() {
        assertEquals(100, LiveZoom.snap(null))
        assertEquals(100, LiveZoom.snap(-50))
        assertEquals(100, LiveZoom.snap(99))
        assertEquals(125, LiveZoom.snap(120))
        assertEquals(100, LiveZoom.snap(112))
        assertEquals(125, LiveZoom.snap(113))
        assertEquals(300, LiveZoom.snap(900))
        assertEquals(300, LiveZoom.snap(290))
        assertEquals(200, LiveZoom.snap(200))
    }

    @Test
    fun `a memory that fails does not stop the zoom`() {
        val broken = object : ZoomMemory {
            override var percent: Int
                get() = error("cannot read")
                set(_) = error("cannot write")
        }
        val zoom = LiveZoom(broken)
        assertEquals(100, zoom.percent)
        assertTrue(zoom.step(1))
        assertEquals(125, zoom.percent)
    }

    @Test
    fun `the zoom kept is the one applied to the first fit of the next projection`() {
        val fit = FrameFit.Mapping(RectPx(0, 0, 800, 480), RectPx(0, 0, 526, 300))
        val zoom = LiveZoom(Memory(200))
        assertEquals("the bottom edge of the source stays", RectPx(200, 240, 400, 240), zoom.apply(fit).source)
    }
}
