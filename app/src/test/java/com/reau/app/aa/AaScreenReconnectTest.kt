package com.reau.app.aa

import com.reau.app.video.LookGate
import com.reau.app.video.PictureLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The first moments of the screen watch ([AaScreenWatch.look]: 3 s from the first look, not read) when a projection is started again in the same
 * process. The compositor outlives Android Auto's sessions and goes on looking at its old picture after one ends, so a look of that picture marked
 * the start of the next projection's 3 s and the window came out 0 to 1.3 s (8 of 23 projections of 12 to 14/11/2026). The compositor's side is
 * played here by [look] and [picture] with the real [LookGate], and the service's by [stop] and [start], in the order LiveSides does them.
 */
class AaScreenReconnectTest {
    private val gate = LookGate()
    private val watch = AaScreens.watch

    @Before
    fun clean() = watch.reset()

    @After
    fun tearDown() = watch.reset()

    /** A look of the compositor: handed on only when the gate is open. */
    private fun look(kind: PictureLook.Kind, at: Long): AaScreen? = if (gate.open) watch.look(kind, at) else null

    /** A picture of Android Auto was latched, and the look the compositor takes of it. */
    private fun picture(kind: PictureLook.Kind, at: Long): AaScreen? {
        gate.pictureArrived()
        return look(kind, at)
    }

    private fun stop() = AaScreens.projection(false)

    private fun start() {
        gate.awaitPicture()
        AaScreens.projectionStarting()
    }

    /** The first projection of a process: the window is 3 s from the first picture, two looks of a map after it make the navigation. */
    @Test
    fun `the first projection counts its 3 seconds from its first picture`() {
        start()
        assertNull("the old nothing: no picture yet, no look", look(PictureLook.Kind.MAP, 500))
        assertNull(picture(PictureLook.Kind.MAP, 1_000))
        assertNull(look(PictureLook.Kind.MAP, 3_999))
        assertNull("the picture starts counting at 4 s", look(PictureLook.Kind.MAP, 4_000))
        assertEquals(AaScreen.NAV, look(PictureLook.Kind.MAP, 4_500))
    }

    /** The looks of an old picture, after the end and before the next picture, do not start the next projection's window. */
    @Test
    fun `a projection started again does not inherit the look at the old picture that came after the end`() {
        start()
        picture(PictureLook.Kind.MAP, 1_000)
        look(PictureLook.Kind.MAP, 4_000)
        assertEquals(AaScreen.NAV, look(PictureLook.Kind.MAP, 4_500))
        stop()
        look(PictureLook.Kind.MAP, 20_000)
        look(PictureLook.Kind.MAP, 20_250)
        start()
        assertNull("the compositor's look at the old picture, before the new one", look(PictureLook.Kind.MAP, 21_000))
        assertNull(picture(PictureLook.Kind.MAP, 22_000))
        assertNull("2.9 s after the first picture", look(PictureLook.Kind.MAP, 24_900))
        assertEquals("no screen before the window ends", AaScreen.UNKNOWN, watch.screen.value)
        assertNull("3 s: the first look that counts", look(PictureLook.Kind.MAP, 25_000))
        assertEquals(AaScreen.NAV, look(PictureLook.Kind.MAP, 25_500))
    }

    /** The old look far before the new picture made the window 0 (the first look listened at once). */
    @Test
    fun `a stale look long before the new picture does not take the window to zero`() {
        start()
        picture(PictureLook.Kind.MAP, 1_000)
        stop()
        look(PictureLook.Kind.MAP, 20_000)
        start()
        assertNull(picture(PictureLook.Kind.MAP, 30_000))
        assertNull(look(PictureLook.Kind.MAP, 30_500))
        assertNull(look(PictureLook.Kind.MAP, 32_900))
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        assertNull(look(PictureLook.Kind.MAP, 33_000))
        assertEquals(AaScreen.NAV, look(PictureLook.Kind.MAP, 33_500))
    }

    /** Whatever the gate lets through, the start itself forgets what the watch took in since the end. */
    @Test
    fun `the start of a projection forgets a look taken after the end`() {
        picture(PictureLook.Kind.MAP, 1_000)
        stop()
        watch.look(PictureLook.Kind.MAP, 20_000)
        AaScreens.projectionStarting()
        assertNull(watch.look(PictureLook.Kind.MAP, 30_000))
        assertNull(watch.look(PictureLook.Kind.MAP, 32_900))
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MAP, 33_000))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 33_500))
    }

    @Test
    fun `the gate is open until a projection holds it, shut until a picture arrives`() {
        val g = LookGate()
        assertEquals(true, g.open)
        g.awaitPicture()
        assertEquals(false, g.open)
        g.awaitPicture()
        assertEquals("held twice is still held", false, g.open)
        g.pictureArrived()
        assertEquals(true, g.open)
        g.pictureArrived()
        assertEquals("a picture with no hold changes nothing", true, g.open)
    }
}
