package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug build's 6/3 experiment ([com.reau.app.settings.REauSettings.experimentFpsSixThree]) as the stream governor lives it: off, the full profile is 4 fps and the reduced
 * one 2, as always; on, 6 and 3 (the one odd rate), the bitrates the same, the encoder built once for 6 fps, a change of profile only a change of pace, and the keyframes
 * of the second kept at 3 fps. What reaches the governor from the settings is Fps63SwitchTest's.
 */
class Fps63GovernorTest {
    private var nowMs = 0L

    private fun governor(on: Boolean): StreamGovernor =
        if (on) {
            StreamGovernor(
                DashConfig.EXPERIMENT_FPS63_FULL_FPS, bitrateCeilingBps = DashConfig.PROFILE_FULL_BPS, keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC,
                lightScreenFps = DashConfig.LIGHT_SCREEN_FPS, exactHalfReduced = true,
            )
        } else {
            StreamGovernor(
                DashConfig.PROFILE_FULL_FPS, bitrateCeilingBps = DashConfig.PROFILE_FULL_BPS, keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC,
                lightScreenFps = DashConfig.LIGHT_SCREEN_FPS,
            )
        }

    /** One poll 250 ms after the one before, with [drawn] new pictures (the stream moves when there are some). */
    private fun StreamGovernor.poll(drawn: Int = 1, navSample: StreamGovernor.NavSample? = null): List<Action> {
        nowMs += POLL_MS
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs,
                compositor = DashCompositor.Stats(sourceFrames = drawn * 2, drawn = drawn, repeated = 0, composeNs = drawn * 2_000_000L, tickWaitsMs = emptyList()),
                framesOut = drawn, bytesOut = drawn * 1_000L, lateFrames = 0, encodeNs = drawn * 8_000_000L,
                dashAcks = 0L, firstFrameConfirmed = true, navSample = navSample,
            ),
        ).filterNot { it is Action.Stats }
    }

    private fun maneuverSample(distance: Int?) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** A started governor, one picture in, then 10 s of the maneuver profile near the maneuver and the far baseline: the next change of tier is not held back. */
    private fun started(on: Boolean, drawn: Int = 1): StreamGovernor = governor(on).also {
        it.start(nowMs)
        it.poll(drawn)
        repeat(41) { _ -> it.poll(drawn, maneuverSample(distance = 1200)) }
        it.poll(drawn, maneuverSample(distance = 1200))
    }

    /** The governor in the reduced tier (250 m past the baseline), and what that poll said. */
    private fun StreamGovernor.reduce(drawn: Int = 1): List<Action> = poll(drawn, maneuverSample(distance = 1450))

    @Test
    fun `off, the full profile is 4 fps and the reduced one 2, as always`() {
        val g = started(on = false)
        assertEquals(4, g.fps)
        assertEquals(4, g.encoderFps)
        g.reduce()
        assertEquals(2, g.fps)
        assertEquals(2, g.profileFps)
    }

    @Test
    fun `on, the full profile is 6 fps and the reduced one 3, the exact half`() {
        val g = started(on = true)
        assertEquals(6, g.fps)
        assertEquals(6, g.profileFps)
        g.reduce()
        assertEquals(3, g.fps)
        assertEquals(3, g.profileFps)
    }

    @Test
    fun `without the switch the odd half is still rounded down to an even step, even at 6 fps, so the exception is the experiment's alone`() {
        val g = StreamGovernor(6, lightScreenFps = 6).also { it.start(nowMs); it.poll(1) }
        repeat(41) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        g.reduce()
        assertEquals(2, g.fps)
    }

    @Test
    fun `the clock ticks at 6 fps in the full profile and at 3 in the reduced one, with the idle tick twice that`() {
        val g = governor(on = true).also { it.start(nowMs) }
        assertEquals(listOf(Action.SetPace(DashCompositor.Pace(167, 334, 0))), g.poll(drawn = 0))
        repeat(41) { _ -> g.poll(0, maneuverSample(distance = 1200)) }
        assertEquals(6, g.fps)
        val reduced = g.reduce(drawn = 0)
        assertTrue("got $reduced", Action.SetPace(DashCompositor.Pace(333, 666, 0)) in reduced)
    }

    @Test
    fun `the encoder is built once for 6 fps, in both profiles, and a change of profile never asks for another`() {
        val g = governor(on = true).also { it.start(nowMs); it.poll(1); it.poll(1, maneuverSample(distance = 1200)) }
        assertEquals(6, g.encoderFps)
        assertEquals(6, g.rebuildAction().fps)
        val all = mutableListOf<Action>()
        // each tier is kept 10 s before the other may be taken: 41 polls
        for (distance in listOf(1450, 400, 1500, 1750, 300)) {
            repeat(41) { _ -> all += g.poll(1, maneuverSample(distance)) }
            assertEquals("at $distance m, whichever profile it is", 6, g.encoderFps)
            assertEquals("at $distance m, a rebuild would ask for 6 too", 6, g.rebuildAction().fps)
        }
        assertTrue("got ${all.filterIsInstance<Action.Rebuild>()}", all.none { it is Action.Rebuild })
        assertEquals(
            "reduced, full, reduced, full: only the pace moves, between 3 and 6 fps",
            listOf(333L, 167L, 333L, 167L), all.filterIsInstance<Action.SetPace>().map { it.pace.activeMs },
        )
    }

    @Test
    fun `the bitrates do not move, 400 kbps in the full profile, 200 in the reduced one and 400 again on the way back`() {
        val g = started(on = true)
        assertEquals(400_000, g.activeBitrateBps)
        val reduced = g.reduce()
        assertTrue("got $reduced", Action.SetBitrate(200_000) in reduced)
        assertEquals("what the encoder is built for stays the full profile's", 400_000, g.activeBitrateBps)
        val back = (1..41).flatMap { _ -> g.poll(1, maneuverSample(distance = 300)) }
        assertTrue("got $back", Action.SetBitrate(400_000) in back)
        assertEquals(6, g.fps)
    }

    @Test
    fun `no frame gets less than the least bits, 400 kbps at 6 fps and 200 kbps at 3`() {
        val least = DashLogic.MIN_BITS_PER_FRAME
        assertTrue("400 kbps over 6 fps", DashConfig.PROFILE_FULL_BPS / DashConfig.EXPERIMENT_FPS63_FULL_FPS >= least)
        assertTrue("200 kbps over 3 fps", DashConfig.PROFILE_REDUCED_BPS / DashConfig.EXPERIMENT_FPS63_REDUCED_FPS >= least)
        assertEquals("the full profile's bitrate is not raised for 6 fps", 400_000, DashLogic.effectiveActiveBitrate(DashConfig.PROFILE_FULL_BPS, 6))
        assertEquals("a bitrate under what 6 fps needs is raised to the stop that gives it", 300_000, DashLogic.effectiveActiveBitrate(200_000, 6))
        assertTrue(DashLogic.effectiveActiveBitrate(200_000, 6).toLong() >= 6L * least)
        assertEquals(400_000, governor(on = true).activeBitrateBps)
    }

    @Test
    fun `the full profile at 6 fps asks for no keyframe of its own, the encoder's count of 6 frames is the second`() {
        val g = started(on = true)
        assertEquals("the interval is the rider's second, not stretched", 1, g.encoderKeyframeSec)
        assertEquals(6, g.gopFps)
        assertEquals(false, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        val asked = (1..40).flatMap { _ -> g.poll() }.filterIsInstance<Action.RequestSync>()
        assertTrue("got $asked", asked.isEmpty())
    }

    @Test
    fun `the reduced profile at 3 fps has the loop ask for a keyframe a second, since 6 frames would be 2 s`() {
        val g = started(on = true)
        g.reduce()
        assertEquals(3, g.fps)
        assertEquals(true, DashLogic.paceSyncNeeded(g.fps, g.encoderFps, g.gopFps))
        val asked = (1..40).flatMap { _ -> g.poll(1, maneuverSample(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        // 10 s of polls: one every 1000 ms (a poll is 250 ms, so exactly every fourth), the first a second after the last
        assertTrue("got ${asked.size}: $asked", asked.size in 9..10)
        assertTrue("got $asked", asked.all { it.reason == "pace" })
    }

    @Test
    fun `a still map in the reduced profile at 3 fps sends its idle keyframe every 2 s, a repeat every 666 ms`() {
        val g = started(on = true, drawn = 0)
        g.reduce(drawn = 0)
        assertEquals(3, g.fps)
        assertEquals(666L, DashLogic.frameIntervalsMsForFps(g.fps).second)
        val asked = (1..40).flatMap { _ -> g.poll(0, maneuverSample(distance = 1450)) }.filterIsInstance<Action.RequestSync>()
        assertTrue("got ${asked.size}: $asked", asked.size in 4..5)
        assertTrue("got $asked", asked.all { it.reason == "idle" })
    }

    @Test
    fun `a heat-capped stream at 4 fps keeps its 2 fps reduced profile even with the switch on, the exact half of what it has`() {
        val g = StreamGovernor(4, lightScreenFps = 6, exactHalfReduced = true).also { it.start(nowMs); it.poll(1) }
        repeat(41) { _ -> g.poll(1, maneuverSample(distance = 1200)) }
        g.reduce()
        assertEquals(4, g.encoderFps)
        assertEquals(2, g.fps)
    }

    @Test
    fun `the light screens stay at 6 fps in both profiles, and the profile's own pace is back when they leave`() {
        for ((on, reducedFps) in listOf(false to 2, true to 3)) {
            val g = started(on)
            g.reduce()
            assertEquals("on=$on", reducedFps, g.fps)
            g.onPoll(lightPoll())
            assertEquals("on=$on, on a light screen", 6, g.fps)
            assertEquals("on=$on, the profile's pace is kept under it", reducedFps, g.profileFps)
            assertEquals(6, DashConfig.LIGHT_SCREEN_FPS)
            repeat(4) { _ -> g.poll(1, maneuverSample(distance = 1450)) }
            assertEquals("on=$on, back to the profile's", reducedFps, g.fps)
        }
    }

    private fun lightPoll(): StreamGovernor.Poll {
        nowMs += POLL_MS
        return StreamGovernor.Poll(
            nowMs = nowMs, compositor = DashCompositor.Stats(sourceFrames = 2, drawn = 1, repeated = 0, composeNs = 2_000_000L, tickWaitsMs = emptyList()),
            framesOut = 1, bytesOut = 1_000L, lateFrames = 0, encodeNs = 8_000_000L, dashAcks = 0L, firstFrameConfirmed = true,
            navSample = maneuverSample(distance = 1450), lightScreen = true,
        )
    }

    private companion object {
        const val POLL_MS = 250L
    }
}
