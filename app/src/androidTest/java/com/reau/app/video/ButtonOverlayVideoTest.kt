package com.reau.app.video

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the last-button text writes is in the video that goes to the dash, as [StatsOverlayVideoTest] does for the stats: the compositor
 * runs with no Android Auto picture (black frames, so the only orange is the text), feeds the real dash encoder, and what comes out is
 * decoded and looked at: orange letters in the box, nothing before the first press, the text of the last press only, nothing when off.
 */
@RunWith(AndroidJUnit4::class)
class ButtonOverlayVideoTest {
    private class Out(val data: ByteArray, val ptsMs: Long)

    private val log = CopyOnWriteArrayList<String>()
    private val out = CopyOnWriteArrayList<Out>()
    private var compositor: DashCompositor? = null
    private var encoder: DashEncoder? = null

    @After
    fun tearDown() {
        compositor?.release()
        encoder?.release()
    }

    private fun run(overlay: ButtonOverlay, text: ButtonText?, whileRunning: (DashCompositor) -> Unit = {}): H264TestDecoder.Picture {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        c.setPace(DashCompositor.Pace(62, 250))
        c.setButtonText(text)
        c.setButtonOverlay(overlay)
        val enc = DashEncoder { data, _, ptsMs -> out += Out(data, ptsMs) }.also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        c.setOutput(checkNotNull(enc.inputSurface)) { pts -> enc.frameSubmitted(pts) }
        Thread.sleep(SETTLE_MS)
        whileRunning(c)
        Thread.sleep(SETTLE_MS)
        c.clearOutput()
        enc.release()
        val pictures = H264TestDecoder().decode(out.map { H264TestDecoder.Chunk(it.data, it.ptsMs) })
        assertTrue("nothing was decoded from ${out.size} buffers; $log", pictures.isNotEmpty())
        return pictures.last()
    }

    /** Orange: strong red, middling green, little blue (the codec blurs the exact 0xFF8C00). */
    private fun isOrange(color: Int) = Color.red(color) > 180 && Color.green(color) in 80..210 && Color.blue(color) < 100 && Color.red(color) - Color.green(color) > 40

    private fun oranges(picture: H264TestDecoder.Picture, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(picture.height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(picture.width)) if (isOrange(picture.colorAt(x, y))) n++
        }
        return n
    }

    @Test
    fun theCodeIsOrangeInsideItsBoxAndNowhereElse() {
        val overlay = ButtonOverlay(true, 100, 120, 30)
        val text = ButtonText("0x12", "JoystickBack", "map up")
        val (w, h) = ButtonOverlayInk.measure(text, overlay.size)
        val picture = run(overlay, text)
        val inside = oranges(picture, overlay.x, overlay.y, overlay.x + w, overlay.y + h)
        val outside = oranges(picture, 0, 0, picture.width, picture.height) - inside
        assertTrue("orange pixels in the box: $inside", inside > 150)
        assertTrue("orange pixels outside the box: $outside", outside <= STRAY)
    }

    @Test
    fun noPressYetWritesNothing() {
        val picture = run(ButtonOverlay(true, 100, 120, 30), null)
        assertTrue("orange pixels: ${oranges(picture, 0, 0, picture.width, picture.height)}", oranges(picture, 0, 0, picture.width, picture.height) <= STRAY)
    }

    @Test
    fun switchedOffNothingIsWritten() {
        val picture = run(ButtonOverlay(false, 100, 120, 30), ButtonText("0x05", "JoystickIn"))
        assertTrue("orange pixels: ${oranges(picture, 0, 0, picture.width, picture.height)}", oranges(picture, 0, 0, picture.width, picture.height) <= STRAY)
    }

    @Test
    fun theLastPressStaysAndTheNextOneReplacesIt() {
        val overlay = ButtonOverlay(true, 100, 120, 30)
        val first = ButtonText("0x13", "ZoomOut with a long detail that makes it wide")
        val second = ButtonText("0x05", "Tap")
        val (firstW, h) = ButtonOverlayInk.measure(first, overlay.size)
        val (secondW, _) = ButtonOverlayInk.measure(second, overlay.size)
        assertTrue("the test needs different widths", firstW > secondW + 40)
        val picture = run(overlay, first) { it.setButtonText(second) }
        assertTrue("the second text is there", oranges(picture, overlay.x, overlay.y, overlay.x + secondW, overlay.y + h) > 100)
        assertTrue("the first text's far end is gone", oranges(picture, overlay.x + secondW + 8, overlay.y, overlay.x + firstW, overlay.y + h) <= STRAY)
    }

    @Test
    fun switchedOffWhileRunningTheTextGoes() {
        val overlay = ButtonOverlay(true, 100, 120, 30)
        val picture = run(overlay, ButtonText("0x05", "JoystickIn")) { c ->
            Thread.sleep(SETTLE_MS)
            c.setButtonOverlay(overlay.copy(on = false))
        }
        assertTrue("orange pixels: ${oranges(picture, 0, 0, picture.width, picture.height)}", oranges(picture, 0, 0, picture.width, picture.height) <= STRAY)
    }

    private companion object {
        const val SETTLE_MS = 1_200L

        /** Codec blur may leave a few orange-ish pixels beside the letters. */
        const val STRAY = 8
    }
}
