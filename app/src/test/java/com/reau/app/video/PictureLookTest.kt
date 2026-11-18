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
 * which also have the retrainings; the four outputs of 17/11/2026 replaced the three of 10/11/2026, and the expected values below are the four-output net's: the same pictures, run through the same C reference).
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

    /** Gray [v] with a lighter frame (96) on the left and on top: just over the limit of 0,9 for the net. */
    private fun grayWithEdge(v: Int) = rgba { x, y -> if (x < 8 || y < 4) 96 else v }

    /** Gray [v] on the top half and [v] + 40 below: just under the limit of 0,9 for the net when [v] is 48. */
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
        val nav = ScreenNet.Screen.NAV
        val menu = ScreenNet.Screen.MENU
        val spotify = ScreenNet.Screen.SPOTIFY
        val other = ScreenNet.Screen.OTHER
        val unsure = ScreenNet.Screen.UNSURE
        check("black", flat(0), Expected(PictureLook.Kind.UNSURE, unsure, 0f, floatArrayOf(0.441469014f, 0.532338262f, -4.38581991f, 3.68386197f)))
        check("white", flat(255), Expected(PictureLook.Kind.MAP, nav, 0.995860457f, floatArrayOf(4.67090893f, -3.61211133f, -3.91430616f, -0.92388773f)))
        check("gray 46", flat(46), Expected(PictureLook.Kind.APPS, other, 0.968424499f, floatArrayOf(-0.622571409f, -4.59371996f, -0.097496748f, 3.79745793f)))
        check("noise", noise(), Expected(PictureLook.Kind.UNSURE, unsure, 0.871895909f, floatArrayOf(-1.98248124f, 4.60605526f, 2.67867064f, -6.1414814f)))
        check("gradient", gradient(), Expected(PictureLook.Kind.APPS, spotify, 0.900832117f, floatArrayOf(0.325516135f, -1.38584065f, 2.70985293f, -3.94291186f)))
        check("black with bars", bars(), Expected(PictureLook.Kind.UNSURE, unsure, 0.84280318f, floatArrayOf(3.1362524f, -0.492825925f, -5.5337944f, 1.30244732f)))
        check("dark over white", darkOverWhite(), Expected(PictureLook.Kind.MAP, nav, 0.999477625f, floatArrayOf(7.14153719f, -6.7543087f, -1.03582597f, -1.19005311f)))
        check("white over dark", rgba { _, y -> if (y < h / 2) 255 else 0 }, Expected(PictureLook.Kind.UNSURE, unsure, 0.808787882f, floatArrayOf(0.982171834f, 0.445790768f, -5.01566792f, 2.88638783f)))
        check("gray 63 with a lighter edge", grayWithEdge(63), Expected(PictureLook.Kind.UNSURE, unsure, 0.828594506f, floatArrayOf(0.324827641f, -5.30339766f, -0.0303505957f, 2.43388247f)))
        check("dark top 48", darkTop(48), Expected(PictureLook.Kind.UNSURE, unsure, 0.403555125f, floatArrayOf(1.59409034f, -6.70220947f, 1.8553865f, 1.50969577f)))
        check("menu grid", menuGrid(), Expected(PictureLook.Kind.MENU, menu, 0.998319566f, floatArrayOf(-5.4595089f, 7.28407574f, -1.83925653f, 0.828200817f)))
        check("spotify-like", spotifyLike(), Expected(PictureLook.Kind.APPS, spotify, 0.999964714f, floatArrayOf(-3.87947416f, -2.55076051f, 8.38833523f, -2.87414455f)))
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
        val result = ScreenNet.classify(noise(), w, h)
        val logits = ScreenNet.lastLogits()
        assertTrue("the leaning class would be the menu", logits[1] > logits[0] && logits[1] > logits[2] && logits[1] > logits[3])
        assertEquals(ScreenNet.Screen.UNSURE, result.screen)
        assertTrue("confidence ${result.confidence}", result.confidence < ScreenNet.MIN_CONFIDENCE && result.confidence > 0.85f)
        val over = ScreenNet.classify(gradient(), w, h)
        assertTrue("just over the limit it is the class: confidence ${over.confidence}", over.confidence > ScreenNet.MIN_CONFIDENCE && over.confidence < 0.92f)
        assertEquals(ScreenNet.Screen.SPOTIFY, over.screen)
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
