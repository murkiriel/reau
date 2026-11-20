package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.video.DashEncoder
import com.reau.app.settings.REauSettings
import com.reau.app.settings.VideoPace
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two paces of the video (plan 32, 20/11/2026) as the stream governor lives them: REau (A 6 fps, B 3) and Original (A 4, B 2), both at the same 400 kbps
 * ceiling, with a keyframe every second, the light screens never above A's pace, and the encoder built once for A's pace. The governor is built as the video
 * loop builds it ([VideoLoop.Settings.withRider]): the pace of A as the ceiling and [VideoPace.lightScreenFps]. The REau pace is what PaceGovernorTest and
 * GovernadorOuroTest lock; this one adds the Original and the comparison between them.
 */
class VideoPaceGovernorTest {
    private var nowMs = 0L

    private fun governor(pace: VideoPace): StreamGovernor {
        val s = VideoLoop.Settings().withRider(REauSettings(videoPace = pace).sanitized(false))
        return StreamGovernor(s.ceilingFps, s.clockSlowPercent, s.windowMs, s.bitrateBps, s.keyframeSec, lightScreenFps = s.lightScreenFps)
    }

    private fun StreamGovernor.poll(drawn: Int = 1, navSample: StreamGovernor.NavSample? = null, light: Boolean = false): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L, tickWaitsMs = emptyList()),
                framesOut = drawn, bytesOut = drawn * 1_000L, lateFrames = 0, encodeNs = drawn * 8_000_000L,
                dashAcks = 0L, firstFrameConfirmed = true, navSample = navSample, lightScreen = light,
            ),
        ).filterNot { it is Action.Stats }
    }

    private fun maneuver(distance: Int?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    private fun started(pace: VideoPace): StreamGovernor = governor(pace).also {
        it.start(nowMs)
        it.poll(1)
        repeat(41) { _ -> it.poll(1, maneuver(distance = 1200)) }
        it.poll(1, maneuver(distance = 1200))
    }

    private fun StreamGovernor.reduce(light: Boolean = false) = poll(1, maneuver(distance = 1450), light)

    @Test
    fun `A and B run at 6 and 3 in the REau pace and at 4 and 2 in the Original, B the exact half`() {
        for ((pace, ab) in listOf(VideoPace.REAU to (6 to 3), VideoPace.ORIGINAL to (4 to 2))) {
            val g = started(pace)
            assertEquals("$pace A", ab.first, g.fps)
            assertEquals("$pace encoder built for A", ab.first, g.encoderFps)
            g.reduce()
            assertEquals("$pace B", ab.second, g.fps)
            assertEquals("$pace B is the half", g.encoderFps / 2, g.profileFps)
            assertEquals("$pace the encoder stays A's", ab.first, g.encoderFps)
            assertEquals("$pace back to A", ab.first, (1..60).map { _ -> g.poll(1, maneuver(distance = 300)); g.fps }.last())
        }
    }

    @Test
    fun `the Original's clock ticks at 4 fps in A and 2 in B, with the idle tick twice that`() {
        val g = governor(VideoPace.ORIGINAL).also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(250, 500, 0))), g.poll(drawn = 0))
        repeat(41) { _ -> g.poll(0, maneuver(distance = 1200)) }
        val reduced = g.reduce()
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(500, 1000, 0)) in reduced)
    }

    @Test
    fun `the REau pace still ticks at 6 and 3 as the golden traces have it`() {
        val g = governor(VideoPace.REAU).also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(167, 334, 0))), g.poll(drawn = 0))
        repeat(41) { _ -> g.poll(0, maneuver(distance = 1200)) }
        assertTrue(Action.SetPace(DashCompositor.Pace(333, 666, 0)) in g.reduce())
    }

    @Test
    fun `the floor of the navigation profile does not hold the Original's B above 2`() {
        assertTrue(StreamGovernor.NAV_MIN_FPS <= VideoPace.ORIGINAL.reducedFps)
        assertEquals(2, StreamGovernor.NAV_MIN_FPS)
        val g = started(VideoPace.ORIGINAL).also { it.reduce() }
        assertEquals(2, g.fps)
    }

    @Test
    fun `both paces have the same 400 kbps ceiling in A, in B, at the start and on a still picture`() {
        for (pace in VideoPace.entries) {
            val g = governor(pace)
            val start = g.start(nowMs)
            assertTrue("$pace: got $start", Action.SetBitrate(400_000) in start)
            assertEquals(400_000, g.activeBitrateBps)
            val all = (1..80).flatMap { _ -> g.poll(1, maneuver(distance = 1450)) } + (1..80).flatMap { _ -> g.poll(0) }
            assertTrue("$pace: no change of the bitrate: got ${all.filterIsInstance<Action.SetBitrate>()}", all.none { it is Action.SetBitrate || it is Action.Rebuild })
            assertEquals(400_000, g.activeBitrateBps)
            assertEquals(400_000, g.rebuildAction().bitrateBps)
        }
        assertEquals(400_000, DashConfig.PROFILE_FULL_BPS)
    }

    @Test
    fun `the keyframe is every second in both paces, at 4 fps too`() {
        for (pace in VideoPace.entries) {
            val g = started(pace)
            assertEquals(pace.fullFps, g.gopFps)
            assertEquals("$pace: asked of the encoder as 1 s", 1, g.encoderKeyframeSec)
            assertEquals(1, g.keyframeSec)
            assertEquals(1, g.rebuildAction().keyframeSec)
            assertEquals("$pace: the interval is not stretched", 1, g.rebuildAction().encoderKeyframeSec)
        }
        assertEquals(1, DashLogic.encoderKeyframeSec(keyframeSec = 1, gopFps = 4, encoderFps = 4))
        assertEquals("the log line of the encoder: 4 frames at the hint", "1 (every 4 frames at the hint)", DashEncoder.iFrameIntervalText(1, 4))
        // at its own pace A leaves the keyframes to the encoder; B has the loop ask once a second
        val g = started(VideoPace.ORIGINAL)
        assertFalse(DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        assertTrue((1..40).flatMap { _ -> g.poll() }.filterIsInstance<Action.RequestSync>().isEmpty())
        g.reduce()
        assertTrue(DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        val asked = (1..40).flatMap { _ -> g.poll(1, maneuver(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        assertTrue("got ${asked.size}: $asked", asked.size in 9..10)
    }

    @Test
    fun `a still picture in the Original does not ask for the idle keyframes the encoder already makes`() {
        assertFalse(DashLogic.idleSyncNeeded(hintFps = 4, keyframeIntervalSec = 1, idleFrameMs = 500))
        val g = started(VideoPace.ORIGINAL)
        val still = (1..160).flatMap { _ -> g.poll(drawn = 0) }
        assertTrue("got $still", still.none { it is Action.SetPace || it is Action.Rebuild })
    }

    @Test
    fun `a light screen is never above A's pace, 4 fps in the Original and 6 in the REau`() {
        for (pace in VideoPace.entries) {
            val g = started(pace)
            g.poll(light = true)
            assertEquals("$pace on A", pace.fullFps, g.fps)
            repeat(4) { _ -> g.poll(light = false) }
            g.reduce()
            assertEquals("$pace B", pace.reducedFps, g.fps)
            g.reduce(light = true)
            assertEquals("$pace: the menu in B goes up to A's pace and no higher", pace.fullFps, g.fps)
            assertEquals("and the encoder's interval is not stretched for it", 1, g.encoderKeyframeSec)
            repeat(60) { _ -> g.reduce(light = false) }
            assertEquals(pace.reducedFps, g.fps)
        }
        assertEquals(4, LightScreen.fps(base = 2, light = VideoPace.ORIGINAL.lightScreenFps))
        assertEquals(6, LightScreen.fps(base = 3, light = VideoPace.REAU.lightScreenFps))
        assertEquals("the global cap stays 6", 6, LightScreen.fps(base = 3, light = 12))
    }

    @Test
    fun `the heat ceiling never takes the Original under its 4 fps, and takes the REau down to it at the worst`() {
        for (status in listOf(0, 1, 2, 3, 4, 5)) {
            assertEquals("Original under heat $status", 4, ThermalCeiling.capFor(status, VideoPace.ORIGINAL.fullFps))
            assertTrue("REau under heat $status", ThermalCeiling.capFor(status, VideoPace.REAU.fullFps) in 4..6)
        }
        assertEquals(6, ThermalCeiling.capFor(0, VideoPace.REAU.fullFps))
        assertEquals(4, ThermalCeiling.capFor(3, VideoPace.REAU.fullFps))
    }

    @Test
    fun `the profiles text says the pace of each profile`() {
        assertEquals("profile A 6 fps / 400 kbps, B 3 fps / 400 kbps", governor(VideoPace.REAU).profilesText)
        assertEquals("profile A 4 fps / 400 kbps, B 2 fps / 400 kbps", governor(VideoPace.ORIGINAL).profilesText)
    }

    @Test
    fun `the input burst is as it was, a light screen's business whatever the pace`() {
        assertEquals(InputBurst.GAP_MS, VideoLoop.burstGapFor(VideoPace.ORIGINAL.lightScreenFps, thermalStatus = 0, firstFrameConfirmed = true))
        assertEquals(InputBurst.GAP_MS, VideoLoop.burstGapFor(VideoPace.REAU.lightScreenFps, thermalStatus = 0, firstFrameConfirmed = true))
        assertEquals(0L, VideoLoop.burstGapFor(0, thermalStatus = 0, firstFrameConfirmed = true))
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
