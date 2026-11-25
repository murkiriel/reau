package com.reau.app.stream

import com.reau.app.stream.StreamGovernor.Action
import com.reau.app.video.DashCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The faster pace while Android Auto's app menu is in front: only the pace, with a hold, never over the dash's limit. */
class LightScreenTest {
    // ── the rule ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pace goes up at once on a light screen and comes back after the hold when it stops being one`() {
        val light = LightScreen(holdMs = 500)
        assertFalse(light.update(light = false, thermalOk = true, nowMs = 0))
        assertTrue(light.update(light = true, thermalOk = true, nowMs = 1_000))
        assertTrue("held for the flicker of a read", light.update(light = false, thermalOk = true, nowMs = 1_499))
        assertFalse(light.update(light = false, thermalOk = true, nowMs = 1_500))
    }

    @Test
    fun `the menu and the other apps ask for the faster pace, the navigation and a screen not known follow the profile`() {
        assertTrue(LightScreen.wanted(com.reau.app.aa.AaScreen.MENU))
        assertTrue(LightScreen.wanted(com.reau.app.aa.AaScreen.APPS))
        assertFalse("the navigation follows the profiles", LightScreen.wanted(com.reau.app.aa.AaScreen.NAV))
        assertFalse(LightScreen.wanted(com.reau.app.aa.AaScreen.UNKNOWN))
    }

    @Test
    fun `a light screen seen again restarts the hold`() {
        val light = LightScreen(holdMs = 500)
        light.update(true, true, 0)
        light.update(false, true, 400)
        light.update(true, true, 450)
        assertTrue(light.update(false, true, 900))
        assertFalse(light.update(false, true, 950))
    }

    @Test
    fun `a hot phone puts it out at once and a light screen does not bring it back while hot`() {
        val light = LightScreen()
        assertTrue(light.update(true, true, 0))
        assertFalse(light.update(true, false, 100))
        assertFalse(light.update(true, false, 200))
        assertTrue(light.update(true, true, 300))
    }

    @Test
    fun `the light rate is never below the profile's and never above the 6 fps of profile A`() {
        assertEquals(6, LightScreen.fps(base = 3, light = 6))
        assertEquals("the profile's own pace is kept", 4, LightScreen.fps(base = 4, light = 2))
        assertEquals("asked for more, it stays at 6", 6, LightScreen.fps(base = 3, light = 8))
        assertEquals(6, LightScreen.fps(base = 6, light = 12))
        assertEquals(6, LightScreen.fps(base = 2, light = 30))
    }

    // ── in the governor ────────────────────────────────────────────────────────────────────────────────────

    private var nowMs = 0L
    private var acks = 0L

    private fun governor(lightFps: Int) =
        StreamGovernor(6, lightScreenFps = lightFps).also { it.start(nowMs) }

    private fun far(distance: Int) = StreamGovernor.NavSample(
        maneuverProfileEnabled = true, speedProfileEnabled = false, distanceToManeuverMeters = distance, speedKmh = null,
    )

    /** A governor in the reduced profile B (3 fps): the first verdict, the dwell after it (10 s), then a far maneuver. */
    private fun inProfileB(lightFps: Int = 6): StreamGovernor = governor(lightFps).also {
        repeat(41) { _ -> it.poll(light = false, navSample = far(1_200)) }
        it.poll(light = false, navSample = far(1_450))
    }

    private fun StreamGovernor.poll(light: Boolean, hot: Boolean = false, confirmed: Boolean = true, navSample: StreamGovernor.NavSample? = null, frames: Int = 1): List<Action> {
        nowMs += 250
        acks += frames
        return onPoll(
            StreamGovernor.Poll(
                nowMs = nowMs, compositor = DashCompositor.Stats(frames * 2, frames, 0, 0L), framesOut = frames, bytesOut = 1_000L * frames,
                lateFrames = 0, encodeNs = 0L,
                dashAcks = acks, firstFrameConfirmed = confirmed, thermalOk = !hot, lightScreen = light, navSample = navSample,
            ),
        )
    }

    private fun List<Action>.pace() = filterIsInstance<Action.SetPace>().map { it.pace.activeMs }

    @Test
    fun `on the app menu in profile B the tick goes from 333 ms to 167 ms, and back after the hold, with a line each way and no new encoder`() {
        val g = inProfileB()
        assertEquals(3, g.fps)
        val up = g.poll(light = true, navSample = far(1_450))
        assertEquals(listOf(167L), up.pace())
        assertEquals(6, g.fps)
        assertEquals(6, g.encoderFps)
        assertTrue(up.filterIsInstance<Action.Log>().any { "light screen: pace up to 6 fps" in it.line })
        var back = emptyList<Action>()
        repeat(12) { back = back + g.poll(light = false, navSample = far(1_450)) }
        assertEquals(listOf(333L), back.pace())
        assertEquals(3, g.fps)
        assertTrue(back.filterIsInstance<Action.Log>().any { "light screen: pace back to the profile's 3 fps" in it.line })
        assertTrue("the encoder is never replaced for this", (up + back).none { it is Action.Rebuild })
    }

    @Test
    fun `on the app menu in profile A nothing changes, the pace is already the 6 fps that is the most`() {
        val g = governor(lightFps = 6)
        g.poll(light = false)
        assertEquals(6, g.fps)
        val up = g.poll(light = true)
        assertEquals(6, g.fps)
        assertTrue("no new pace: $up", up.pace().isEmpty())
    }

    @Test
    fun `a light pace asked above 6 is held at 6 in the governor too, on every profile`() {
        for (asked in listOf(8, 10, 12, 30)) {
            val b = inProfileB(lightFps = asked)
            b.poll(light = true, navSample = far(1_450))
            assertEquals("asked $asked, profile B", 6, b.fps)
            val a = governor(lightFps = asked)
            a.poll(light = false)
            a.poll(light = true)
            assertEquals("asked $asked, profile A", 6, a.fps)
        }
    }

    @Test
    fun `with the option off the menu changes nothing`() {
        val g = inProfileB(lightFps = 0)
        val all = (1..8).flatMap { g.poll(light = true, navSample = far(1_450)) }
        assertEquals(3, g.fps)
        assertTrue(all.none { it is Action.Log && "light screen" in it.line })
    }

    @Test
    fun `a hot phone gets no faster pace`() {
        val g = inProfileB()
        g.poll(light = true, hot = true, navSample = far(1_450))
        assertEquals(3, g.fps)
    }

    @Test
    fun `before the dash confirms the picture the pace is the profile's whatever the screen`() {
        val g = governor(lightFps = 6)
        g.poll(light = true, confirmed = false)
        assertEquals("the start ramp: the profile's pace", 6, g.profileFps)
        val b = inProfileB()
        b.poll(light = true, confirmed = false, navSample = far(1_450))
        assertEquals("the menu does not raise B before the dash has shown a frame", 3, b.fps)
        // the light pace needs the dash to have shown a frame; once it has, the menu raises B
        b.poll(light = true, confirmed = true, navSample = far(1_450))
        assertEquals(6, b.fps)
        assertEquals(3, b.profileFps)
    }

    @Test
    fun `in profile B the menu raises the pace and leaves the bitrate alone, and profile B comes back after it`() {
        // 21/10/2026: far from the next maneuver the profile is REDUCED and the rider's menu was a 2 fps picture. Now B is 3 fps at the same 400 kbps.
        val g = inProfileB()
        assertEquals("profile B", 3, g.profileFps)
        assertEquals(3, g.fps)

        val on = g.poll(light = true, navSample = far(1_450))
        assertEquals("the menu leaves profile B's 3 fps behind", 6, g.fps)
        assertEquals(listOf(167L), on.pace())
        assertTrue("and the bitrate is not touched: $on", on.none { it is Action.SetBitrate })

        var back = emptyList<Action>()
        repeat(4) { back = back + g.poll(light = false, navSample = far(1_450)) }
        assertEquals("profile B comes back when the menu is left", 3, g.fps)
        assertEquals(listOf(333L), back.pace())
        assertTrue("with the same bitrate, and never a new encoder: $back", (on + back).none { it is Action.SetBitrate || it is Action.Rebuild })
    }

    @Test
    fun `the heat is judged against the profile's pace, never the faster one that the menu raised`() {
        // The window's thermal flag is judged against profileFps, the profile's own pace: the menu's pace is a pace the heat puts out, not one it judges.
        val g = inProfileB()
        g.poll(light = true, navSample = far(1_450))
        assertEquals(6, g.fps)
        assertEquals(3, g.profileFps)
        assertTrue(com.reau.app.stream.ThermalCeiling.allows(status = 0, fps = g.profileFps, ceiling = 6))
        assertFalse("a moderate heat allows 4 of 6, not the 6 of the menu", com.reau.app.stream.ThermalCeiling.allows(status = 2, fps = g.fps, ceiling = 6))
        assertTrue(java.io.File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText().contains("ThermalCeiling.allows(status, g.profileFps, settings.ceilingFps)"))
    }

    @Test
    fun `a window at the boosted pace is read against the boosted pace, and the stream's full pace stays 6`() {
        val g = inProfileB()
        g.poll(light = true, navSample = far(1_450))
        // One frame and a half in each 250 ms poll is too fine for the bench, so 3 frames every second poll: 6 fps sent and drawn, every window a whole one at the boosted pace.
        val all = (1..120).flatMap { i -> g.poll(light = true, frames = if (i % 2 == 0) 3 else 0, navSample = far(1_450)) }
        val lines = all.filterIsInstance<Action.Log>().map { it.line }.filter { it.startsWith("video fps") }
        assertTrue("windows at the boosted pace: $lines", lines.isNotEmpty() && lines.all { "target=6" in it && "full=true" in it })
        assertTrue("the full pace stays 6, and the window is judged at 6: $lines", lines.all { "auto=6fps [ok]" in it })
        assertTrue("and the encoder stays as built", all.none { it is Action.Rebuild } && g.encoderFps == 6 && g.profileFps == 3)
    }
}
