package com.reau.app.dash

import com.reau.app.dash.DashConfig.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The choices of the video (plan 32): what is offered, the fixed picture, the guide for the bitrate ceiling, the load for the log and the meaning of the old profile ids.
 * How the choices are stored and reach the stream is VideoChoiceStoreTest's; what the governor does with them is PaceGovernorTest's.
 */
class VideoChoiceTest {
    private val full = FrameSize(528, 304)

    // ---- what is offered

    @Test
    fun `the paces are 4, 6 and 8, the ceilings 400 to 1000 by 100 and the defaults 6 and 400`() {
        assertEquals(listOf(4, 6, 8), VideoChoice.FPS_OPTIONS)
        assertEquals(listOf(400, 500, 600, 700, 800, 900, 1000), VideoChoice.CAP_OPTIONS_KBPS)
        assertEquals(6, VideoChoice.DEFAULT_FPS)
        assertEquals(400, VideoChoice.DEFAULT_CAP_KBPS)
        assertTrue("200 kbps is not offered", 200 !in VideoChoice.CAP_OPTIONS_KBPS && VideoChoice.CAP_MIN_KBPS == 400)
    }

    @Test
    fun `the picture is the fixed 528x304, the dash's own 526x300 aligned to 16`() {
        assertEquals(FrameSize(528, 304), VideoChoice.MANUAL_FRAME)
        assertEquals("528x304", VideoChoice.MANUAL_FRAME.id)
        assertEquals("526x300", DashConfig.FULL_FRAME.id)
        assertEquals(
            listOf(VideoChoice.MANUAL_FRAME.width, VideoChoice.MANUAL_FRAME.height),
            listOf((DashConfig.FULL_FRAME.width + 15) / 16 * 16, (DashConfig.FULL_FRAME.height + 15) / 16 * 16),
        )
    }

    @Test
    fun `a pace outside the offer is brought to the nearest end, an odd one to the even pace below, and a ceiling to the nearest step`() {
        assertEquals(4, VideoChoice.clampFps(0))
        assertEquals(4, VideoChoice.clampFps(-4))
        assertEquals(4, VideoChoice.clampFps(2))
        assertEquals("an odd pace reads as the even one below it", 6, VideoChoice.clampFps(7))
        assertEquals("10 fps, gone, reads as 8", 8, VideoChoice.clampFps(10))
        assertEquals("12 fps, gone, reads as 8", 8, VideoChoice.clampFps(12))
        assertEquals(8, VideoChoice.clampFps(14))
        assertEquals(8, VideoChoice.clampFps(30))
        for (fps in VideoChoice.FPS_OPTIONS) assertEquals(fps, VideoChoice.clampFps(fps))
        assertEquals(400, VideoChoice.snapCapKbps(200))
        assertEquals(400, VideoChoice.snapCapKbps(0))
        assertEquals(1000, VideoChoice.snapCapKbps(1024))
        assertEquals(1000, VideoChoice.snapCapKbps(5000))
        assertEquals(500, VideoChoice.snapCapKbps(450))
        assertEquals(400, VideoChoice.snapCapKbps(449))
        for (k in VideoChoice.CAP_OPTIONS_KBPS) assertEquals(k, VideoChoice.snapCapKbps(k))
    }

    @Test
    fun `only 8 fps may flicker the reserve icon, because it is above the reserve's 6`() {
        assertFalse(VideoChoice.mayFlickerReserveIcon(4))
        assertFalse(VideoChoice.mayFlickerReserveIcon(6))
        assertFalse(VideoChoice.mayFlickerReserveIcon(DashConfig.RESERVE_CAP_FPS))
        assertTrue(VideoChoice.mayFlickerReserveIcon(7))
        assertTrue(VideoChoice.mayFlickerReserveIcon(8))
        assertEquals(listOf(8), VideoChoice.FPS_OPTIONS.filter { VideoChoice.mayFlickerReserveIcon(it) })
    }

    // ---- the load (only for the ride log)

    @Test
    fun `the macroblocks are counted whole, so 526x300 and 528x304 are the same 627`() {
        assertEquals(627, VideoChoice.macroblocks(full))
        assertEquals(627, VideoChoice.macroblocks(DashConfig.FULL_FRAME))
        assertEquals(448, VideoChoice.macroblocks(FrameSize(448, 256)))
        assertEquals(30 * 17, VideoChoice.macroblocks(FrameSize(480, 272)))
        assertEquals(1, VideoChoice.macroblocks(FrameSize(1, 1)))
        assertEquals(4, VideoChoice.macroblocks(FrameSize(17, 17)))
    }

    @Test
    fun `the load is the pace times the macroblocks`() {
        assertEquals(3762, VideoChoice.loadMbPerSec(6, full))
        assertEquals(2508, VideoChoice.loadMbPerSec(4, full))
        assertEquals(3584, VideoChoice.loadMbPerSec(8, FrameSize(448, 256)))
        assertEquals(5016, VideoChoice.loadMbPerSec(8, full))
    }

    // ---- the guide

    @Test
    fun `the guide keeps the bits per frame of 6 fps at 400 kbps`() {
        assertEquals(400, VideoChoice.recommendedKbps(4))
        assertEquals(400, VideoChoice.recommendedKbps(6))
        assertEquals("8 fps is the owner's 600", 600, VideoChoice.recommendedKbps(8))
        assertEquals(500, VideoChoice.recommendedKbps(7))
    }

    @Test
    fun `the guide is 400 up to 6 fps, never under the floor`() {
        for (fps in listOf(1, 2, 3, 4, 5, 6)) assertEquals("$fps fps", 400, VideoChoice.recommendedKbps(fps))
    }

    @Test
    fun `the guide of every pace is a hundred between 400 and 1000, and grows with the pace`() {
        val byPace = VideoChoice.FPS_OPTIONS.map { VideoChoice.recommendedKbps(it) }
        assertEquals(byPace.sorted(), byPace)
        for ((fps, k) in VideoChoice.FPS_OPTIONS.zip(byPace)) {
            assertEquals("$fps", 0, k % 100)
            assertTrue("$fps: $k", k in 400..1000)
        }
    }

    @Test
    fun `the guide rounds UP to a hundred, and a value exactly on a hundred stays`() {
        // 400 x 7 / 6 = 466.7: 500
        assertEquals(500, VideoChoice.recommendedKbps(7))
        // 400 x 9 / 6 is exactly 600
        assertEquals(600, VideoChoice.recommendedKbps(9))
        // 400 x 15 / 6 is exactly 1000, not 1100
        assertEquals(1000, VideoChoice.recommendedKbps(15))
        assertEquals("never over the slider's top", 1000, VideoChoice.recommendedKbps(30))
    }

    // ---- the old profile ids

    @Test
    fun `each id of the first build means two choices, and 12 reads as 8 fps at its own 800`() {
        assertEquals(VideoChoice.Choice(4, 400), VideoChoice.fromLegacyProfile("4"))
        assertEquals(VideoChoice.Choice(6, 400), VideoChoice.fromLegacyProfile("6"))
        assertEquals(VideoChoice.Choice(8, 600), VideoChoice.fromLegacyProfile("8"))
        assertEquals("8m only lost its smaller picture", VideoChoice.Choice(8, 600), VideoChoice.fromLegacyProfile("8m"))
        assertEquals("12 fps is gone: 8 fps, the bitrate kept", VideoChoice.Choice(8, 800), VideoChoice.fromLegacyProfile("12"))
        for (bad in listOf("", "5", "8M", "12 ", "x")) assertNull("id '$bad'", VideoChoice.fromLegacyProfile(bad))
        assertNull(VideoChoice.fromLegacyProfile(null))
    }

    @Test
    fun `an old pace alone is read as the first profile of that pace, any other as 4`() {
        assertEquals(VideoChoice.fromLegacyProfile("4"), VideoChoice.fromLegacyPace(4))
        assertEquals(VideoChoice.fromLegacyProfile("6"), VideoChoice.fromLegacyPace(6))
        assertEquals(VideoChoice.fromLegacyProfile("8"), VideoChoice.fromLegacyPace(8))
        assertEquals(VideoChoice.fromLegacyProfile("12"), VideoChoice.fromLegacyPace(12))
        for (pace in listOf(0, 5, 30, -1)) assertEquals("pace $pace", VideoChoice.fromLegacyProfile("4"), VideoChoice.fromLegacyPace(pace))
        assertNotNull(VideoChoice.fromLegacyProfile("4"))
    }

    // ---- the reserve's ceiling in the config

    @Test
    fun `the reserve's ceiling is 6 fps and 400 kbps and the light screens 6`() {
        assertEquals(6, DashConfig.RESERVE_CAP_FPS)
        assertEquals(400_000, DashConfig.RESERVE_CAP_BPS)
        assertEquals(6, DashConfig.LIGHT_SCREEN_FPS)
    }

    @Test
    fun `the release build's values are 4 fps, 400 kbps and 526x300, and nothing in the stream's limits stops an offered pace`() {
        assertEquals(listOf(4, 400_000, 2), listOf(DashConfig.PROFILE_FULL_FPS, DashConfig.PROFILE_FULL_BPS, DashConfig.PROFILE_REDUCED_FPS))
        for (fps in VideoChoice.FPS_OPTIONS) {
            assertEquals("the stream's range keeps $fps fps", fps, DashConfig.snapStreamFps(fps))
            assertTrue(fps in DashConfig.MIN_STREAM_FPS..DashConfig.MAX_STREAM_FPS)
        }
    }
}
