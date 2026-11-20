package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pace of the debug build's FPS test card ([StreamGovernor.testFps]): it is above the profiles, the light screens and (in the loop) the input burst, under the heat's
 * ceiling only; the bitrate and the picture stay fixed; the keyframe stays at 1 s; and with the card off everything is as it was.
 */
class FpsTestCardGovernorTest {
    private var nowMs = 0L

    /** The app's own stream: profile A 6 fps at 400 kbps, a keyframe every second, the light screens at 6 (what [com.reau.app.settings.REauSettings.lightScreenFps] gives). */
    private fun started(): StreamGovernor =
        StreamGovernor(DashConfig.PROFILE_FULL_FPS, bitrateCeilingBps = DashConfig.PROFILE_FULL_BPS, keyframeSec = 1, lightScreenFps = DashConfig.LIGHT_SCREEN_FPS)
            .also { it.start(nowMs) }

    private fun StreamGovernor.poll(
        fpsTest: Int? = null,
        asked: Int? = fpsTest,
        lightScreen: Boolean = false,
        navSample: StreamGovernor.NavSample? = null,
    ): List<Action> {
        nowMs += 250
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(0, 0, 0, 0L), framesOut = 0, bytesOut = 0L, lateFrames = 0, encodeNs = 0L,
                dashAcks = 0L, firstFrameConfirmed = true, lightScreen = lightScreen, navSample = navSample, fpsTest = fpsTest, fpsTestAsked = asked,
            ),
        ).filterNot { it is Action.Stats }
    }

    private fun List<Action>.pace() = filterIsInstance<Action.SetPace>().map { it.pace }
    private fun List<Action>.rebuilds() = filterIsInstance<Action.Rebuild>()
    private fun List<Action>.lines() = filterIsInstance<Action.Log>().map { it.line }

    private fun maneuver(distance: Int) =
        StreamGovernor.NavSample(maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null)

    @Test
    fun `with the card off nothing changes and no line is written`() {
        val g = started()
        val first = g.poll()
        assertEquals(listOf(DashCompositor.Pace(167, 334, 0)), first.pace())
        assertTrue(first.rebuilds().isEmpty())
        assertNull(g.testFps)
        assertEquals(6, g.fps)
        assertTrue(g.poll().isEmpty())
    }

    @Test
    fun `the card sets the pace of the stream at the pace chosen, 4 to 12`() {
        for (rate in listOf(4, 6, 8, 10, 12)) {
            val g = started()
            val actions = g.poll(fpsTest = rate)
            assertEquals(rate, g.fps)
            assertEquals(rate, g.testFps)
            val (active, idle) = DashLogic.frameIntervalsMsForFps(rate)
            assertEquals("$rate", listOf(DashCompositor.Pace(active, idle, 0)), actions.pace())
        }
    }

    @Test
    fun `the card ignores the light screens and the reduced profile`() {
        val g = started()
        // The first verdict (far from a maneuver), kept 10 s, then the distance moves by 200 m: profile B, 3 fps.
        repeat(41) { g.poll(navSample = maneuver(5_000)) }
        g.poll(navSample = maneuver(5_300))
        assertEquals(3, g.fps)
        g.poll(fpsTest = 10, navSample = maneuver(5_300), lightScreen = true)
        assertEquals(10, g.fps)
        g.poll(fpsTest = 10, navSample = maneuver(5_300), lightScreen = true)
        assertEquals("still 10 on a light screen", 10, g.fps)
        // The card went off at once, but the light screen's own hold ([LightScreen.HOLD_MS], 500 ms) is what keeps 6 for the next two polls: that is the light screen's, not the card's.
        g.poll(fpsTest = null, navSample = maneuver(5_300))
        assertNull(g.testFps)
        assertEquals("the light screen's hold, not the card", 6, g.fps)
        g.poll(fpsTest = null, navSample = maneuver(5_300))
        g.poll(fpsTest = null, navSample = maneuver(5_300))
        assertEquals("the profile again with the card off", 3, g.fps)
    }

    @Test
    fun `off again, the stream goes back to the profile's pace with a line, and no new stream`() {
        val g = started()
        g.poll(fpsTest = 8)
        val off = g.poll(fpsTest = null)
        assertEquals(6, g.fps)
        assertNull(g.testFps)
        assertEquals(listOf(DashCompositor.Pace(167, 334, 0)), off.pace())
        assertTrue(off.lines().single().startsWith("fps test card: off"))
        assertTrue("no bitrate change, no new stream: ${off}", off.none { it is Action.SetBitrate })
    }

    @Test
    fun `the bitrate ceiling does not move with the card, at any pace`() {
        for (rate in listOf(4, 12)) {
            val g = started()
            val actions = g.poll(fpsTest = rate)
            assertTrue(actions.none { it is Action.SetBitrate })
            assertEquals(400_000, g.activeBitrateBps)
            assertEquals(6, g.encoderFps)
        }
    }

    @Test
    fun `a pace above the encoder's asks for a new encoder with a longer keyframe interval, so the keyframe stays at 1 s, and off asks for the old one back`() {
        val g = started()
        assertEquals(1, g.encoderKeyframeSec)
        val on = g.poll(fpsTest = 12)
        // 12 fps counted at the encoder's 6: 1 s x 12 / 6, and one more second so its own keyframes never come first.
        assertEquals(listOf(Action.Rebuild(6, 1, 400_000, 3)), on.rebuilds())
        assertEquals(1, g.keyframeSec)
        assertTrue("the same interval again is no new encoder", g.poll(fpsTest = 12).rebuilds().isEmpty())
        assertEquals(listOf(Action.Rebuild(6, 1, 400_000, 1)), g.poll(fpsTest = null).rebuilds())
    }

    @Test
    fun `a pace at or under the encoder's needs no new encoder`() {
        for (rate in listOf(4, 6)) {
            val g = started()
            assertTrue("$rate", g.poll(fpsTest = rate).rebuilds().isEmpty())
            assertEquals(1, g.encoderKeyframeSec)
        }
    }

    @Test
    fun `a card on at the start is in the encoder's interval from the first build`() {
        val g = StreamGovernor(6, bitrateCeilingBps = 400_000, keyframeSec = 1, lightScreenFps = 6)
        g.useTestRate(8, 8)
        g.start(nowMs)
        assertEquals(2, g.encoderKeyframeSec)
        assertEquals("no rebuild for what the encoder was already built for", emptyList<Action>(), g.poll(fpsTest = 8).rebuilds())
    }

    @Test
    fun `when the heat lowers the pace the line says what was asked`() {
        val g = started()
        val lowered = g.poll(fpsTest = 8, asked = 12)
        assertEquals(8, g.fps)
        assertTrue(lowered.lines().toString(), lowered.lines().any { it.contains("pace 8 fps") && it.contains("12 asked") && it.contains("heat") })
        val plain = started().poll(fpsTest = 8)
        assertTrue(plain.lines().toString(), plain.lines().none { it.contains("asked") })
    }

    @Test
    fun `the heat's steps bring the pace asked down, never above it`() {
        assertEquals(12, VideoLoop.testRateUnderHeat(12, 0))
        assertEquals(12, VideoLoop.testRateUnderHeat(12, 1))
        assertEquals(8, VideoLoop.testRateUnderHeat(12, 2))
        assertEquals(6, VideoLoop.testRateUnderHeat(12, 3))
        assertEquals(6, VideoLoop.testRateUnderHeat(12, 4))
        assertEquals(4, VideoLoop.testRateUnderHeat(4, 4))
        assertEquals(4, VideoLoop.testRateUnderHeat(4, 0))
        for (rate in listOf(4, 6, 8, 10, 12)) for (status in -1..6) assertTrue(VideoLoop.testRateUnderHeat(rate, status) <= rate)
    }

    @Test
    fun `the pace of the card is a pace of its own in the bitrate by pace line`() {
        // The poll that carried frames at the card's pace is counted under it, apart from profile A at 6.
        val again = started()
        again.poll(fpsTest = 6)
        again.onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs + 250, compositor = DashCompositor.Stats(0, 0, 0, 0L), framesOut = 2, bytesOut = 5_000L, lateFrames = 0, encodeNs = 0L,
                dashAcks = 0L, firstFrameConfirmed = true, fpsTest = 6, fpsTestAsked = 6,
            ),
        )
        assertTrue(again.paceSummary().toString(), again.paceSummary()!!.contains("test 6 fps"))
    }
}
