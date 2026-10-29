package com.reau.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PictureLook] and [ScreenNet] on synthetic pictures only (no frame of anybody's route is in the repository). The pictures are ones the study fed to its
 * independent reference implementation in C (a small program in C, same float32 sums): the expected logits and confidences below come from it, so
 * any change to a weight, a feature or the calibration shows here. What the net gets right on real screens was measured on 5830 recorded frames (see [PictureLook]).
 */
class PictureLookTest {
    private val w = PictureLook.SAMPLE_W
    private val h = PictureLook.SAMPLE_H

    private fun rgba(pixel: (x: Int, y: Int) -> Int): ByteArray = ByteArray(w * h * 4).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val v = pixel(x, y)
            px[o] = v.toByte(); px[o + 1] = v.toByte(); px[o + 2] = v.toByte(); px[o + 3] = 0xFF.toByte()
        }
    }

    private fun flat(v: Int) = rgba { _, _ -> v }

    private fun noise(): ByteArray {
        var s = 12345L
        val px = ByteArray(w * h * 4)
        for (i in 0 until w * h * 3) {
            s = (s * 1103515245L + 12345L) and 0x7fffffffL
            val o = (i / 3) * 4 + i % 3
            px[o] = ((s shr 16) and 0xff).toByte()
        }
        for (p in 0 until w * h) px[p * 4 + 3] = 0xFF.toByte()
        return px
    }

    private fun gradient() = rgba { x, y -> (x * 7 + y * 3) % 256 }

    /** Black with white-ish bars on the left and on top: about 80% black. */
    private fun bars() = rgba { x, y -> if (x < 8 || y < 4) 200 else 0 }

    private class Expected(val kind: PictureLook.Kind, val confidence: Float, val logits: FloatArray)

    private fun check(name: String, picture: ByteArray, expected: Expected) {
        assertEquals(name, expected.kind, PictureLook.of(picture, w, h))
        val result = ScreenNet.classify(picture, w, h)
        assertEquals("$name: confidence", expected.confidence, result.confidence, 1e-3f)
        assertArrayEquals("$name: calibrated logits", expected.logits, ScreenNet.lastLogits(), 1e-4f)
    }

    @Test
    fun `the net says what the independent C reference says on eight synthetic pictures`() {
        check("black", flat(0), Expected(PictureLook.Kind.UNSURE, 0f, floatArrayOf(-2.15150213f, -1.32794356f, 3.43750048f)))
        check("white", flat(255), Expected(PictureLook.Kind.MAP, 0.999097884f, floatArrayOf(5.26076937f, -4.44450378f, -1.81901467f)))
        check("gray 46", flat(46), Expected(PictureLook.Kind.APPS, 0.91509819f, floatArrayOf(0.516384244f, -4.69596577f, 2.8993547f)))
        check("noise", noise(), Expected(PictureLook.Kind.MENU, 0.99818027f, floatArrayOf(-6.79629803f, 6.75322199f, 0.445248306f)))
        check("gradient", gradient(), Expected(PictureLook.Kind.MAP, 0.993755281f, floatArrayOf(4.54567432f, -4.72915888f, -0.539116859f)))
        check("black with bars", bars(), Expected(PictureLook.Kind.APPS, 0.998783529f, floatArrayOf(-4.17935371f, -1.59787083f, 5.18564272f)))
        check("gradient upside down", rgba { x, y -> (x * 7 + (h - 1 - y) * 3) % 256 },
            Expected(PictureLook.Kind.UNSURE, 0.490010262f, floatArrayOf(0.585192323f, -2.38946676f, 0.574847758f)))
        check("gray 80 with a lighter edge", rgba { x, _ -> if (x < 4) 96 else 80 },
            Expected(PictureLook.Kind.UNSURE, 0.849539757f, floatArrayOf(1.17809892f, -4.83161116f, 2.91154671f)))
    }

    @Test
    fun `the four kinds all come out`() {
        val kinds = listOf(flat(0), flat(255), noise(), bars()).map { PictureLook.of(it, w, h) }
        assertEquals(listOf(PictureLook.Kind.UNSURE, PictureLook.Kind.MAP, PictureLook.Kind.MENU, PictureLook.Kind.APPS), kinds)
    }

    @Test
    fun `a picture that is not sure enough (confidence under 0,9) is unsure, not the class it leans to`() {
        val result = ScreenNet.classify(rgba { x, _ -> if (x < 4) 96 else 80 }, w, h)
        assertTrue("the leaning class would be APPS", ScreenNet.lastLogits()[2] > ScreenNet.lastLogits()[0])
        assertEquals(ScreenNet.Screen.UNSURE, result.screen)
        assertTrue("confidence ${result.confidence}", result.confidence < ScreenNet.MIN_CONFIDENCE && result.confidence > 0.82f)
        val gray = ScreenNet.classify(flat(46), w, h)
        assertTrue("just over the limit it is the class: confidence ${gray.confidence}", gray.confidence > ScreenNet.MIN_CONFIDENCE && gray.confidence < 0.95f)
        assertEquals(ScreenNet.Screen.APPS, gray.screen)
    }

    @Test
    fun `from 92 percent of black pixels the net is not even asked, and below it is`() {
        // a white picture (a map for the net) with some pixels painted black: 92% of 2560 = 2355.2, so 2356 black pixels shut the gate and 2355 do not
        fun withBlack(n: Int): ByteArray = rgba { x, y -> if (y * w + x < n) 0 else 255 }
        val shut = ScreenNet.classify(withBlack(2356), w, h)
        assertEquals(ScreenNet.Screen.UNSURE, shut.screen)
        assertEquals("the gate answers without a confidence", 0f, shut.confidence, 0f)
        val open = ScreenNet.classify(withBlack(2355), w, h)
        assertTrue("the net answered: confidence ${open.confidence}", open.confidence > 0f)
    }

    @Test
    fun `a pixel is black below luma 16 and not from 16`() {
        assertEquals("all at 15: black", 0f, ScreenNet.classify(flat(15), w, h).confidence, 0f)
        assertTrue("all at 16: not black, the net answers", ScreenNet.classify(flat(16), w, h).confidence > 0f)
    }

    @Test
    fun `a picture of another size or a short array is unsure`() {
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(10), w, h))
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(128 * 80 * 4), 128, 80))
        assertEquals(PictureLook.Kind.UNSURE, PictureLook.of(ByteArray(w * h * 4 - 1), w, h))
    }

    @Test
    fun `the net looks at where things are, so a picture upside down is another picture`() {
        val upright = gradient()
        val upsideDown = rgba { x, y -> (x * 7 + (h - 1 - y) * 3) % 256 }
        assertEquals(PictureLook.Kind.MAP, PictureLook.of(upright, w, h))
        assertTrue("turned over it is not read as the map", PictureLook.of(upsideDown, w, h) != PictureLook.Kind.MAP)
        assertArrayEquals("the same two pictures, one the other upside down", upright, PictureLook.topDown(upsideDown, w, h))
        assertEquals("what glReadPixels gives, turned, is read as the picture it shows", PictureLook.Kind.MAP, PictureLook.of(PictureLook.topDown(upsideDown, w, h), w, h))
    }

    @Test
    fun `topDown puts the last row first and keeps every pixel`() {
        val bottomUp = ByteArray(2 * 3 * 4) { (it / 8).toByte() }       // 2 pixels a row, 3 rows: the rows are 0, 1, 2 from the bottom
        val top = PictureLook.topDown(bottomUp, 2, 3)
        assertArrayEquals(ByteArray(8) { 2 } + ByteArray(8) { 1 } + ByteArray(8) { 0 }, top)
        assertArrayEquals("twice is the same picture", bottomUp, PictureLook.topDown(top, 2, 3))
        assertEquals("the input is left alone", 0.toByte(), bottomUp[0])
    }

    @Test
    fun `topDown refuses an array that is not width times height of RGBA`() {
        assertThrows(IllegalArgumentException::class.java) { PictureLook.topDown(ByteArray(10), 2, 3) }
    }
}
