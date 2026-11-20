package com.reau.app.dash

import com.reau.app.dash.DashConfig.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three choices of the video (plan 32): what is offered, the guide for the bitrate ceiling, the load of a mix and the meaning of the old profile ids.
 * How the choices are stored and reach the stream is VideoChoiceStoreTest's; what the governor does with them is PaceGovernorTest's.
 */
class VideoChoiceTest {
    private val full = FrameSize(528, 304)

    // ---- what is offered

    @Test
    fun `the paces are 4, 6, 8, 10 and 12, the ceilings 400 to 1000 by 100 and the defaults 6, 400 and 528x304`() {
        assertEquals(listOf(4, 6, 8, 10, 12), VideoChoice.FPS_OPTIONS)
        assertEquals(listOf(400, 500, 600, 700, 800, 900, 1000), VideoChoice.CAP_OPTIONS_KBPS)
        assertEquals(6, VideoChoice.DEFAULT_FPS)
        assertEquals(400, VideoChoice.DEFAULT_CAP_KBPS)
        assertEquals(FrameSize(528, 304), VideoChoice.DEFAULT_FRAME)
        assertTrue("no pace under 4 or over 12, and none that is odd", VideoChoice.FPS_OPTIONS.all { it in 4..12 && it % 2 == 0 })
        assertTrue("200 kbps is not offered", 200 !in VideoChoice.CAP_OPTIONS_KBPS && VideoChoice.CAP_MIN_KBPS == 400)
    }

    @Test
    fun `the pictures are the six of the owner, all multiples of 16, the first the dash's own aligned, none of 368x208`() {
        assertEquals(
            listOf("528x304", "512x288", "496x288", "480x272", "464x272", "448x256"),
            VideoChoice.FRAMES.map { it.id },
        )
        for (f in VideoChoice.FRAMES) {
            assertEquals("${f.id} wide", 0, f.width % 16)
            assertEquals("${f.id} high", 0, f.height % 16)
        }
        assertTrue("368x208 is gone", VideoChoice.FRAMES.none { it.width == 368 })
        assertEquals("528x304 is the 526x300 aligned to 16", listOf(528, 304), listOf((DashConfig.FULL_FRAME.width + 15) / 16 * 16, (DashConfig.FULL_FRAME.height + 15) / 16 * 16))
        assertEquals("the ids are unique", VideoChoice.FRAMES.size, VideoChoice.FRAMES.map { it.id }.toSet().size)
    }

    @Test
    fun `a picture is found by its id among the offered, and the dash's own 526x300 is not one of them`() {
        for (f in VideoChoice.FRAMES) assertEquals(f, VideoChoice.offeredFrame(f.id))
        assertNull(VideoChoice.offeredFrame("526x300"))
        assertNull(VideoChoice.offeredFrame("368x208"))
        assertNull(VideoChoice.offeredFrame(""))
        assertNull(VideoChoice.offeredFrame(null))
        assertEquals("526x300", DashConfig.FULL_FRAME.id)
    }

    @Test
    fun `a pace and a ceiling outside the offer are brought to the nearest end or step`() {
        assertEquals(4, VideoChoice.clampFps(0))
        assertEquals(4, VideoChoice.clampFps(-4))
        assertEquals(4, VideoChoice.clampFps(2))
        assertEquals("an odd pace reads as the even one below it", 6, VideoChoice.clampFps(7))
        assertEquals(12, VideoChoice.clampFps(14))
        assertEquals(12, VideoChoice.clampFps(30))
        for (fps in VideoChoice.FPS_OPTIONS) assertEquals(fps, VideoChoice.clampFps(fps))
        assertEquals(400, VideoChoice.snapCapKbps(200))
        assertEquals(400, VideoChoice.snapCapKbps(0))
        assertEquals(1000, VideoChoice.snapCapKbps(1024))
        assertEquals(1000, VideoChoice.snapCapKbps(5000))
        assertEquals(500, VideoChoice.snapCapKbps(450))
        assertEquals(400, VideoChoice.snapCapKbps(449))
        for (k in VideoChoice.CAP_OPTIONS_KBPS) assertEquals(k, VideoChoice.snapCapKbps(k))
    }

    // ---- the load

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
    fun `the load is the pace times the macroblocks, and 3762 is 6 fps in the full picture`() {
        assertEquals(3762, VideoChoice.SAFE_LOAD_MB_PER_SEC)
        assertEquals(3762, VideoChoice.loadMbPerSec(6, full))
        assertEquals(3584, VideoChoice.loadMbPerSec(8, FrameSize(448, 256)))
        assertEquals(5016, VideoChoice.loadMbPerSec(8, full))
    }

    @Test
    fun `a mix is heavy only above 3762, so 6 fps in the full picture and 8 fps in 448x256 are not`() {
        assertFalse(VideoChoice.heavy(6, full))
        assertFalse(VideoChoice.heavy(8, FrameSize(448, 256)))
        assertFalse(VideoChoice.heavy(4, full))
        assertTrue(VideoChoice.heavy(7, full))
        assertTrue(VideoChoice.heavy(8, full))
        assertTrue(VideoChoice.heavy(12, full))
        assertTrue("10 fps in 448x256 is 4480", VideoChoice.heavy(10, FrameSize(448, 256)))
        assertTrue("12 fps in 448x256 is 5376", VideoChoice.heavy(12, FrameSize(448, 256)))
        assertTrue("8 fps in 480x272 is 4080", VideoChoice.heavy(8, FrameSize(480, 272)))
        assertFalse("7 fps in 464x272 is 3451", VideoChoice.heavy(7, FrameSize(464, 272)))
        assertTrue("one macroblock a second over the line", VideoChoice.heavy(1, FrameSize(16 * 3763, 16)))
        assertFalse("and exactly on the line", VideoChoice.heavy(1, FrameSize(16 * 3762, 16)))
    }

    // ---- the guide

    @Test
    fun `the guide keeps the bits per macroblock of 6 fps at 400 kbps in the full picture`() {
        assertEquals(400, VideoChoice.recommendedKbps(6, full))
        assertEquals("8 fps in 528x304 is the owner's 600", 600, VideoChoice.recommendedKbps(8, full))
        assertEquals(500, VideoChoice.recommendedKbps(7, full))
        assertEquals(400, VideoChoice.recommendedKbps(8, FrameSize(448, 256)))
        assertEquals(500, VideoChoice.recommendedKbps(8, FrameSize(480, 272)))
        assertEquals(700, VideoChoice.recommendedKbps(10, full))
        assertEquals("12 fps in 528x304 is 800", 800, VideoChoice.recommendedKbps(12, full))
        assertEquals(600, VideoChoice.recommendedKbps(12, FrameSize(448, 256)))
    }

    @Test
    fun `the guide is 400 for 6 fps in any picture, and never under the floor`() {
        for (f in VideoChoice.FRAMES) assertEquals(f.id, 400, VideoChoice.recommendedKbps(6, f))
        for (fps in listOf(2, 3, 4, 5, 6)) for (f in VideoChoice.FRAMES) assertEquals("$fps fps ${f.id}", 400, VideoChoice.recommendedKbps(fps, f))
    }

    @Test
    fun `the guide of every mix is a hundred between 400 and 1000, and grows with the pace and with the picture`() {
        for (fps in VideoChoice.FPS_OPTIONS) {
            var before = 0
            for (f in VideoChoice.FRAMES.sortedBy { VideoChoice.macroblocks(it) }) {
                val k = VideoChoice.recommendedKbps(fps, f)
                assertEquals("$fps ${f.id}", 0, k % 100)
                assertTrue("$fps ${f.id}: $k", k in 400..1000)
                assertTrue("a larger picture never asks for less: $fps ${f.id}", k >= before)
                before = k
            }
        }
        for (f in VideoChoice.FRAMES) {
            val byPace = VideoChoice.FPS_OPTIONS.map { VideoChoice.recommendedKbps(it, f) }
            assertEquals(f.id, byPace.sorted(), byPace)
        }
    }

    @Test
    fun `the guide rounds UP to a hundred, and a value exactly on a hundred stays`() {
        // 400 x 7 x 627 / (6 x 627) = 466.7: 500
        assertEquals(500, VideoChoice.recommendedKbps(7, full))
        // 6 x 1.5 fps would be exact, but a macroblock count that gives exactly 600 does: 400 x 9 / 6 = 600 at 9 fps in the full picture
        assertEquals(600, VideoChoice.recommendedKbps(9, full))
        // 8 fps in 33x(h) macroblocks: 400 x 8 x 33 x 16 / 3762 = 449.1 -> 500
        assertEquals(500, VideoChoice.recommendedKbps(8, FrameSize(528, 256)))
        assertEquals("never over the slider's top", 1000, VideoChoice.recommendedKbps(30, full))
    }

    // ---- the old profile ids

    @Test
    fun `each id of the first build means three choices, and 12 loses its 368x208 picture only`() {
        assertEquals(VideoChoice.Choice(4, 400, full), VideoChoice.fromLegacyProfile("4"))
        assertEquals(VideoChoice.Choice(6, 400, full), VideoChoice.fromLegacyProfile("6"))
        assertEquals(VideoChoice.Choice(8, 600, full), VideoChoice.fromLegacyProfile("8"))
        assertEquals(VideoChoice.Choice(8, 600, FrameSize(448, 256)), VideoChoice.fromLegacyProfile("8m"))
        assertEquals("12 keeps its pace and bitrate, in the closest picture", VideoChoice.Choice(12, 800, FrameSize(448, 256)), VideoChoice.fromLegacyProfile("12"))
        for (bad in listOf("", "5", "8M", "12 ", "x")) assertNull("id '$bad'", VideoChoice.fromLegacyProfile(bad))
        assertNull(VideoChoice.fromLegacyProfile(null))
    }

    @Test
    fun `an old pace alone is read as the first profile of that pace, any other as 4`() {
        assertEquals(VideoChoice.fromLegacyProfile("4"), VideoChoice.fromLegacyPace(4))
        assertEquals(VideoChoice.fromLegacyProfile("6"), VideoChoice.fromLegacyPace(6))
        assertEquals("8 is the full picture, not 8m", VideoChoice.fromLegacyProfile("8"), VideoChoice.fromLegacyPace(8))
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
