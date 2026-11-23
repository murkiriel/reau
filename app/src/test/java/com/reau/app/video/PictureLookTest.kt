package com.reau.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PictureLook] and [ScreenNet] on synthetic pictures only (no frame of anybody's route is in the repository). The pictures are ones the study fed to its
 * independent reference implementation in C (a small program in C, same float32 sums): the expected logits and confidences below come from it, so
 * any change to a weight, a feature or the calibration shows here. What the net gets right on real screens was measured on recorded frames of the Android Auto 17 and 18 (see [PictureLook] and [ScreenNet],
 * which also have the retrainings; the four outputs of 17/11/2026 replaced the three of 10/11/2026, and the retraining of 22/11/2026 changed the weights again: the expected values below are the 22/11 net's, the same pictures run through the same C reference).
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

    /** Light squares on black, 8 pixels apart: a grid of icons, which the net reads as the app menu. */
    private fun menuGrid() = rgba { x, y -> if (x % 8 in 2..5 && y % 8 in 2..5) 240 else 0 }

    /** An olive band over olive, with a magenta square in it: a picture no app has, which the net reads as Spotify (a header and a cover in a body). */
    private fun spotifyLike(): ByteArray = ByteArray(w * h * 4).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val rgb = when {
                y < 19 -> intArrayOf(45, 81, 17)
                x in 18..44 && y >= 22 -> intArrayOf(251, 7, 234)
                else -> intArrayOf(127, 104, 18)
            }
            px[o] = rgb[0].toByte(); px[o + 1] = rgb[1].toByte(); px[o + 2] = rgb[2].toByte(); px[o + 3] = 0xFF.toByte()
        }
    }

    /** Dark on top, white below: a map for the net; turned over (white on top) it is not. */
    private fun darkOverWhite() = rgba { _, y -> if (y < h / 2) 0 else 255 }

    /** Black with white-ish bars on the left and on top: about 80% black. */
    private fun bars() = rgba { x, y -> if (x < 8 || y < 4) 200 else 0 }

    /** Gray [v] with a frame of 96 on the left and on top: with [v] 148 the net is just over the limit of 0,9 (the map, 0.914). */
    private fun grayWithEdge(v: Int) = rgba { x, y -> if (x < 8 || y < 4) 96 else v }

    /** Gray [v] on the top half and [v] + 40 below: well under the limit of 0,9 for the net when [v] is 48. */
    private fun darkTop(v: Int) = rgba { _, y -> if (y < h / 2) v else v + 40 }

    private class Expected(val kind: PictureLook.Kind, val screen: ScreenNet.Screen, val confidence: Float, val logits: FloatArray)

    private fun check(name: String, picture: ByteArray, expected: Expected) {
        assertEquals(name, expected.kind, PictureLook.of(picture, w, h))
        val result = ScreenNet.classify(picture, w, h)
        assertEquals("$name: the net's class", expected.screen, result.screen)
        assertEquals("$name: confidence", expected.confidence, result.confidence, 1e-3f)
        assertArrayEquals("$name: calibrated logits", expected.logits, ScreenNet.lastLogits(), 1e-4f)
    }

    @Test
    fun `the net says what the independent C reference says on twelve synthetic pictures`() {
        // values of the 22/11 weights; gradient and white-over-dark changed class with the new weights (synthetic pictures, not real screens)
        val nav = ScreenNet.Screen.NAV
        val menu = ScreenNet.Screen.MENU
        val spotify = ScreenNet.Screen.SPOTIFY
        val other = ScreenNet.Screen.OTHER
        val unsure = ScreenNet.Screen.UNSURE
        check("black", flat(0), Expected(PictureLook.Kind.UNSURE, unsure, 0f, floatArrayOf(0.433394611f, 1.08315051f, -5.04730368f, 3.43430018f)))
        check("white", flat(255), Expected(PictureLook.Kind.MAP, nav, 0.998083293f, floatArrayOf(4.6976614f, -3.75474024f, -2.79271102f, -2.07159686f)))
        check("gray 46", flat(46), Expected(PictureLook.Kind.APPS, other, 0.98541832f, floatArrayOf(-0.991919816f, -4.16231918f, -0.706932783f, 4.08499908f)))
        check("noise", noise(), Expected(PictureLook.Kind.UNSURE, unsure, 0.705390036f, floatArrayOf(-3.86236453f, 5.12712431f, 4.25366879f, -5.49709749f)))
        check("gradient", gradient(), Expected(PictureLook.Kind.UNSURE, unsure, 0.684529722f, floatArrayOf(0.245725065f, 0.134932518f, 1.66542208f, -4.27155828f)))
        check("black with bars", bars(), Expected(PictureLook.Kind.UNSURE, unsure, 0.895520627f, floatArrayOf(3.08743858f, -0.38659215f, -6.14357662f, 0.629123867f)))
        check("dark over white", darkOverWhite(), Expected(PictureLook.Kind.MAP, nav, 0.999376297f, floatArrayOf(6.74319267f, -5.97317886f, -1.06735349f, -1.6986177f)))
        check("white over dark", rgba { _, y -> if (y < h / 2) 255 else 0 }, Expected(PictureLook.Kind.APPS, other, 0.961969793f, floatArrayOf(-0.480604768f, 0.467582583f, -4.59611416f, 4.03019285f)))
        check("gray 63 with a lighter edge", grayWithEdge(63), Expected(PictureLook.Kind.UNSURE, unsure, 0.806194901f, floatArrayOf(0.22348313f, -5.28569221f, 0.150547802f, 2.30839634f)))
        check("dark top 48", darkTop(48), Expected(PictureLook.Kind.UNSURE, unsure, 0.562249959f, floatArrayOf(0.63745594f, -6.164639f, 2.02754879f, 1.39123428f)))
        check("menu grid", menuGrid(), Expected(PictureLook.Kind.MENU, menu, 0.998966098f, floatArrayOf(-4.516747f, 7.23546362f, -2.20629978f, 0.274055183f)))
        check("spotify-like", spotifyLike(), Expected(PictureLook.Kind.APPS, spotify, 0.999637246f, floatArrayOf(-3.49035335f, -0.686475992f, 7.32425308f, -4.1016016f)))
    }

    @Test
    fun `the four kinds all come out, and the net names its five answers`() {
        val pictures = listOf(flat(0), flat(255), menuGrid(), flat(46), spotifyLike())
        assertEquals(
            listOf(PictureLook.Kind.UNSURE, PictureLook.Kind.MAP, PictureLook.Kind.MENU, PictureLook.Kind.APPS, PictureLook.Kind.APPS),
            pictures.map { PictureLook.of(it, w, h) },
        )
        assertEquals(
            listOf(ScreenNet.Screen.UNSURE, ScreenNet.Screen.NAV, ScreenNet.Screen.MENU, ScreenNet.Screen.OTHER, ScreenNet.Screen.SPOTIFY),
            pictures.map { ScreenNet.classify(it, w, h).screen },
        )
    }

    @Test
    fun `Spotify and another app are both the apps for the picture look, and the net hands back which one it was`() {
        val into = ScreenNet.Result()
        assertEquals(PictureLook.Kind.APPS, PictureLook.of(spotifyLike(), w, h, into))
        assertEquals(ScreenNet.Screen.SPOTIFY, into.screen)
        assertEquals(PictureLook.Kind.APPS, PictureLook.of(flat(46), w, h, into))
        assertEquals(ScreenNet.Screen.OTHER, into.screen)
        assertEquals("the map and the menu are not apps", PictureLook.Kind.MAP, PictureLook.of(flat(255), w, h, into))
        assertEquals(ScreenNet.Screen.NAV, into.screen)
    }

    @Test
    fun `only Spotify and the other app are apps for the net`() {
        assertEquals(listOf(ScreenNet.Screen.SPOTIFY, ScreenNet.Screen.OTHER), ScreenNet.Screen.entries.filter { it.isApps })
    }

    @Test
    fun `a picture that is not sure enough (confidence under 0,9) is unsure, not the class it leans to`() {
        val result = ScreenNet.classify(bars(), w, h)
        val logits = ScreenNet.lastLogits()
        assertTrue("the leaning class would be the map", logits[0] > logits[1] && logits[0] > logits[2] && logits[0] > logits[3])
        assertEquals(ScreenNet.Screen.UNSURE, result.screen)
        assertTrue("confidence ${result.confidence}", result.confidence < ScreenNet.MIN_CONFIDENCE && result.confidence > 0.85f)
        val over = ScreenNet.classify(grayWithEdge(148), w, h)
        assertTrue("just over the limit it is the class: confidence ${over.confidence}", over.confidence > ScreenNet.MIN_CONFIDENCE && over.confidence < 0.92f)
        assertEquals(ScreenNet.Screen.NAV, over.screen)
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
        val upright = darkOverWhite()
        val upsideDown = rgba { _, y -> if (y < h / 2) 255 else 0 }
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
