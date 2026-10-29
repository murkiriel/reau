package com.reau.app.web

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.video.EglPainter
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The web viewers' encoder alone (a real MediaCodec fed on its input surface, as the compositor feeds it): it comes up, gives the
 * parameter sets and key frames a viewer starts from, answers a request for a key frame, and lets go cleanly. Its size and rate are
 * its own ([WebEncoder.WIDTH], [WebEncoder.HEIGHT], [WebEncoder.FPS]); nothing here touches the dash's encoder.
 */
@RunWith(AndroidJUnit4::class)
class WebEncoderTest {
    /** One buffer handed to `onOutput`: its size, whether it is a key frame and whether it is the parameter sets. */
    private class Out(val size: Int, val isKey: Boolean, val isConfig: Boolean)

    private val out = CopyOnWriteArrayList<Out>()
    private var encoder: WebEncoder? = null
    private var painter: EglPainter? = null
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)

    @After
    fun tearDown() {
        painter?.release()
        encoder?.release()
    }

    private fun newEncoder() = WebEncoder { bytes, isKey, isConfig -> out += Out(bytes.size, isKey, isConfig) }.also { encoder = it }

    private fun started(): WebEncoder {
        val enc = newEncoder()
        val ok = enc.prepare()
        assertTrue("prepare failed: ${enc.lastError}", ok)
        painter = EglPainter(checkNotNull(enc.inputSurface) { "a prepared encoder must expose its input surface" })
        return enc
    }

    private fun paint(count: Int, firstPtsMs: Long = 0) {
        repeat(count) { i ->
            checkNotNull(painter).paint(colors[i % colors.size], firstPtsMs + i * STEP_MS)
            Thread.sleep(STEP_MS)
        }
    }

    private fun await(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, condition())
    }

    @Test
    fun itComesUpAndExposesItsInputSurface() {
        val enc = newEncoder()
        assertNull("no surface before prepare", enc.inputSurface)
        val ok = enc.prepare()
        assertTrue("prepare failed: ${enc.lastError}", ok)
        assertNotNull(enc.inputSurface)
        assertNull(enc.lastError)
    }

    @Test
    fun aViewerCanStartFromWhatItGives_theParameterSetsAndAKeyFrame() {
        started()
        paint(count = 12)
        await("the parameter sets") { out.any { it.isConfig } }
        await("a key frame") { out.any { it.isKey && !it.isConfig } }
        assertTrue("every buffer has bytes", out.all { it.size > 0 })
    }

    @Test
    fun aRequestForAKeyFrameBringsAnotherOne() {
        val enc = started()
        paint(count = 12)
        await("the first key frame") { out.any { it.isKey && !it.isConfig } }
        val before = out.count { it.isKey && !it.isConfig }
        // Frames keep coming at 30 fps for a moment while the request is made; the next key frame is not the 1 s one of the interval.
        enc.requestSyncFrame()
        paint(count = 10, firstPtsMs = 12 * STEP_MS)
        await("another key frame after the request") { out.count { it.isKey && !it.isConfig } > before }
    }

    @Test
    fun itLetsGoCleanlyAndTwice() {
        val enc = started()
        paint(count = 3)
        painter?.release()
        painter = null
        enc.release()
        assertNull("no surface after release", enc.inputSurface)
        enc.release() // again: nothing is left to release, and nothing is raised
        enc.requestSyncFrame() // nor does a request on a released encoder
    }

    private companion object {
        const val STEP_MS = 33L
    }
}
