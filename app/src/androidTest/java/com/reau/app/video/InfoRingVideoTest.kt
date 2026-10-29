package com.reau.app.video

import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import com.reau.app.weather.RingFrame
import com.reau.app.weather.RingIcon
import com.reau.app.weather.RingKind
import com.reau.app.weather.RingPage
import com.reau.app.weather.RingText
import com.reau.app.weather.RingTone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The info ring is in the video that goes to the dash: a disk of radius 60 at (441, 233), on the right, and gone once taken off. The
 * compositor runs with no Android Auto picture (black frames), feeds the real encoder, and what comes out is decoded and looked at.
 */
@RunWith(AndroidJUnit4::class)
class InfoRingVideoTest {
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

    private fun run(whileRunning: (DashCompositor) -> Unit): H264TestDecoder.Picture {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        c.setPace(DashCompositor.Pace(62, 250))
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

    private fun frame(tone: RingTone, night: Boolean = false) =
        RingFrame(RingPage(RingKind.WIND_DANGER, 1, RingIcon.WIND, tone, listOf(RingText("66", true), RingText("km/h", false))), stale = false, night = night)

    private fun isRed(color: Int) = Color.red(color) > 150 && Color.green(color) < 70 && Color.blue(color) < 90

    private fun isDark(color: Int) = Color.red(color) < 50 && Color.green(color) < 50 && Color.blue(color) < 50

    @Test
    fun aDangerRingIsARedDiskOnTheRightAndTheLeftIsBlack() {
        val picture = run { it.setInfoRing(frame(RingTone.DANGER)) }
        // Inside the disk, left of the icon; and on the left of the frame, where the dash's own bubble is, nothing is drawn.
        assertTrue("red at the ring: ${Integer.toHexString(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y))}", isRed(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y)))
        assertTrue("black on the bubble's side", isDark(picture.colorAt(85, 233)))
        assertTrue("black above the ring", isDark(picture.colorAt(InfoRingSpot.CENTER_X, InfoRingSpot.CENTER_Y - 75)))
    }

    @Test
    fun inThePlaceOfTheManeuverRingTheDiskIsOnTheLeftAndTheRightIsBlack() {
        val picture = run {
            it.setInfoRingPlace(true)
            it.setInfoRing(frame(RingTone.DANGER))
        }
        val bubbleX = DashMask.BUBBLE_X.toInt()
        val bubbleY = DashMask.BUBBLE_Y.toInt()
        assertTrue("red at the bubble's place: ${Integer.toHexString(picture.colorAt(bubbleX - 48, bubbleY))}", isRed(picture.colorAt(bubbleX - 48, bubbleY)))
        assertTrue("black where the ring is on the right", isDark(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y)))
    }

    @Test
    fun backOnTheRightWhenThePlaceIsGivenUp() {
        val picture = run {
            it.setInfoRingPlace(true)
            it.setInfoRing(frame(RingTone.DANGER))
            it.setInfoRingPlace(false)
        }
        assertTrue("red on the right again", isRed(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y)))
        assertTrue("black on the left", isDark(picture.colorAt(DashMask.BUBBLE_X.toInt() - 48, DashMask.BUBBLE_Y.toInt())))
    }

    @Test
    fun theNightOutlineIsPaleRoundTheDisk() {
        val picture = run { it.setInfoRing(frame(RingTone.NONE, night = true)) }
        var pale = 0
        for (x in InfoRingSpot.CENTER_X + 55..InfoRingSpot.CENTER_X + 60) if (Color.red(picture.colorAt(x, InfoRingSpot.CENTER_Y)) > 120) pale++
        assertTrue("pale pixels of the outline: $pale", pale >= 2)
        assertTrue("the night disk is dark", isDark(picture.colorAt(InfoRingSpot.CENTER_X - 45, InfoRingSpot.CENTER_Y)))
    }

    @Test
    fun takenOffWhileRunningItGoes() {
        val picture = run { c ->
            c.setInfoRing(frame(RingTone.DANGER))
            Thread.sleep(SETTLE_MS)
            c.setInfoRing(null)
        }
        assertTrue("black again", isDark(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y)))
    }

    @Test
    fun withoutTheRingTheFrameIsBlack() {
        val picture = run { }
        assertTrue(isDark(picture.colorAt(InfoRingSpot.CENTER_X - 48, InfoRingSpot.CENTER_Y)))
    }

    /**
     * The compositor repeats the last picture only every two seconds here (an idle screen): a ring that comes or goes must not wait for that
     * repeat, it is drawn at once.
     */
    private fun framesAfter(whileRunning: (DashCompositor, () -> Long) -> Unit): List<Long> {
        val c = DashCompositor { log += it }.also { compositor = it }
        assertTrue("compositor did not start: $log", TestPattern.startCompositor(c, log))
        c.setPace(DashCompositor.Pace(62, 2_000))
        val enc = DashEncoder { data, _, ptsMs -> out += Out(data, ptsMs) }.also { encoder = it }
        assertTrue("prepare failed: ${enc.lastPrepareError}", enc.prepare(16, DashConfig.MAX_STREAM_BITRATE_BPS))
        val stamps = CopyOnWriteArrayList<Long>()
        c.setOutput(checkNotNull(enc.inputSurface)) { pts ->
            enc.frameSubmitted(pts)
            stamps += SystemClock.uptimeMillis()
        }
        // Right after a repeat, so the next one is nearly two seconds away.
        val seen = stamps.size
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (stamps.size <= seen && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(300)
        whileRunning(c) { SystemClock.uptimeMillis() }
        return stamps.toList()
    }

    @Test
    fun aRingThatComesIsDrawnAtOnceNotAtTheNextRepeat() {
        var asked = 0L
        val stamps = framesAfter { c, now ->
            asked = now()
            c.setInfoRing(frame(RingTone.DANGER))
            Thread.sleep(700)
        }
        val first = stamps.firstOrNull { it >= asked }
        assertTrue("a frame after the ring came, within 300 ms of it: ${first?.minus(asked)} ms", first != null && first - asked < 300)
    }

    @Test
    fun aRingThatGoesIsTakenOffAtOnceToo() {
        var asked = 0L
        val stamps = framesAfter { c, now ->
            c.setInfoRing(frame(RingTone.DANGER))
            Thread.sleep(1_200)
            asked = now()
            c.setInfoRing(null)
            Thread.sleep(700)
        }
        val first = stamps.firstOrNull { it >= asked }
        assertTrue("a frame after the ring went, within 300 ms of it: ${first?.minus(asked)} ms", first != null && first - asked < 300)
    }

    private companion object {
        const val SETTLE_MS = 1_200L
    }
}
