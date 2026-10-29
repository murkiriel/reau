package com.reau.app.stream

import com.reau.app.dash.DashConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic fps must not outrun the dash. As ride logs showed, the dash does not acknowledge frames one by
 * one, so the controller only saw that the PHONE delivered every frame; once the frame loop stopped waiting for the
 * encoder the phone delivered anything asked of it, the rate climbed
 * 20 → 30 fps in 80 seconds, and at 30 the dash went silent with its Wi-Fi still up. Every reconnection then started
 * straight at the remembered 30 and the dash never decoded a frame: 21 drops in 7 minutes, then 15 more.
 *
 * Two rules came out of that, tested here: a ceiling at what the dash has been seen to hold, and the full rate only
 * once the dash has confirmed the first frame. (A third, a stream the dash stopped answering restarting far lower, was
 * written but never wired into the video loop, and was taken out on 27/10/2026.)
 */
class AutoFpsDashLimitTest {
    private var now = 0L

    private fun AutoFpsController.healthyWindow(): AutoFpsController.Decision {
        now += 5_000
        val fps = currentFps
        return onWindow(AutoFpsController.Window(fps, fps.toDouble(), 0.0, 150.0, allActive = true, thermalOk = true), now)
    }

    // ── the ceiling ────────────────────────────────────────────────────────────

    @Test
    fun `with nothing but the phone's own delivery to go by, it stops at the ceiling`() {
        // Exactly the windows of that ride: sent = target, dash = 0,0 (it does not ack frames).
        val c = AutoFpsController(8, ceiling = 16)
        val reached = (1..200).map { c.healthyWindow().fps }
        assertEquals(16, reached.max())
        assertEquals(16, c.currentFps)
        assertTrue(c.healthyWindow().reason.contains("at ceiling"))
    }

    @Test
    fun `a remembered rate above the ceiling starts at the ceiling`() {
        // What was stored on the phone after that ride: 30.
        assertEquals(16, AutoFpsController(30, ceiling = 16).currentFps)
    }

    @Test
    fun `a ceiling between two steps is the step below it`() {
        assertEquals(14, AutoFpsController(30, ceiling = 15).currentFps)
    }

    @Test
    fun `without a ceiling given it is the slider's own top, as before`() {
        assertEquals(DashConfig.MAX_STREAM_FPS, AutoFpsController(99).currentFps)
    }

    @Test
    fun `the ceiling the app runs with is the highest rate the dash was seen to hold for minutes`() {
        // 16 fps held for minutes without a drop. Above it the stretches were much shorter
        // (about a minute at 18 fps); 30 fps killed the stream.
        assertEquals(16, DashConfig.AUTO_FPS_CEILING)
    }

    // ── starting a stream ──────────────────────────────────────────────────────

    @Test
    fun `a normal start is the remembered rate, under the ceiling`() {
        val plan = AutoFpsController.plan(storedStable = 12)
        assertEquals(AutoFpsController.Plan(startFps = 12, ceiling = 16), plan)
    }

    @Test
    fun `the 30 remembered from before the ceiling existed starts at 16`() {
        assertEquals(16, AutoFpsController.plan(storedStable = 30).startFps)
    }

    // ── the first frame ────────────────────────────────────────────────────────

    @Test
    fun `until the dash confirms the first frame the stream stays at the still-map pace`() {
        // On that ride every stream that began at 4 fps (map still) had its first frame confirmed in about 8 s; of the
        // 36 that began at 30 fps in motion, none was.
        assertFalse(DashLogic.fullRate(mapChangedRecently = true, firstFrameConfirmed = false))
        assertTrue(DashLogic.fullRate(mapChangedRecently = true, firstFrameConfirmed = true))
        assertFalse(DashLogic.fullRate(mapChangedRecently = false, firstFrameConfirmed = true))
        assertFalse(DashLogic.fullRate(mapChangedRecently = false, firstFrameConfirmed = false))
    }
}
