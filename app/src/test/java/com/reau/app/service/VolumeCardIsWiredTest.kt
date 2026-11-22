package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The volume card on the dash's video: what decides it is tested in VolumeCardsTest and VolumeCardLayoutTest. Where it is fed from needs the live sides
 * (a Context, the system's broadcast, the compositor's GL thread), so those lines are read from the source, as the other "...IsWired" tests do.
 */
class VolumeCardIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val watch = File(main, "service/VolumeWatch.kt").readText()
    private val compositor = File(main, "video/DashCompositor.kt").readText()
    private val gl = File(main, "video/CompositorGl.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the watch listens to the volume broadcast and gives it back, and tells each step the card is for`() {
        assertTrue(watch, "IntentFilter(VolumeCards.ACTION)" in watch)
        assertTrue(watch, "app.unregisterReceiver(heard)" in watch)
        assertTrue(watch, "VolumeCards.decide(stream, value, previous, max)?.let(onStep)" in watch)
        assertTrue(watch, "audio.getStreamMaxVolume(stream)" in watch)
    }

    @Test
    fun `the sides start the watch with the projection, only in the real app, and give it back at the release`() {
        val start = live.between("scope.launch { watchWaiting() }", "scope.launch { ProjectionMachine.dashLinks")
        assertTrue(start, "if (setup.settings != null && !volumeWatch.start())" in start)
        assertTrue(live, "\"volume watch\" to { volumeWatch.stop() }" in live)
        assertTrue(live, "private val volumeWatch = VolumeWatch(context, ::volumeStepHeard)" in live)
    }

    @Test
    fun `a step shows the card for 2 s with the log line, the end goes to the compositor, and the card is declared before the init block`() {
        val declaration = live.between("private val volumeCard: com.reau.app.dash.TimedShow<com.reau.app.dash.VolumeNotice> = com.reau.app.dash.TimedShow<com.reau.app.dash.VolumeNotice>(", "private fun volumeCardChanged()")
        assertTrue(declaration, "com.reau.app.dash.VolumeCards.SHOW_MS" in declaration)
        assertTrue(declaration, "val job = scope.launch {\n                delay(delayMs)\n                action()" in declaration)
        assertTrue(declaration, "changed = ::volumeCardChanged" in declaration)
        assertTrue("before the init block", live.indexOf("private val volumeCard: ") < live.indexOf("\n    init {"))
        assertTrue(live, "compositor.setVolumeCard(volumeCard.current())" in live)
        val heard = live.between("private fun volumeStepHeard(", "// The map's zoom from the joystick")
        assertTrue(heard, "volumeCard.show(com.reau.app.dash.VolumeNotice(step.card, text))" in heard && "RideDiagnostics.log(\"dash\", step.logLine())" in heard)
    }

    @Test
    fun `the switch is taken when the projection starts and at each change of the settings`() {
        assertTrue(live, "volumeCard.enabled = setup.settings.volumeCard" in live)
        val retune = live.between("if (LiveTuning.Step.DASH_EXTRAS in steps) {", "if (LiveTuning.Step.DASH_EXTRAS in steps && settings.weatherWanted")
        assertTrue(retune, "volumeCard.enabled = settings.volumeCard" in retune)
    }

    @Test
    fun `the joystick's volume step leaves the title's echo to the card unless the card is off`() {
        val step = live.between("private fun volumeStep(", "/** What the maneuver ring last did")
        assertTrue(step, "VolumeCards.titleEcho(volumeCard.enabled)" in step)
    }

    @Test
    fun `the compositor draws the card over the legend and under the stuck-picture badge, and redraws at once when it changes (the map may be still)`() {
        val draw = compositor.between("if (legend != null) gl.drawLegend()", "val clock = CompositorRules.advance(")
        assertTrue(draw, draw.indexOf("if (volumeCard != null) gl.drawVolumeCard()") in 0 until draw.indexOf("if (staleBadge != null) gl.drawBadge()"))
        val set = compositor.between("fun setVolumeCard(notice: com.reau.app.dash.VolumeNotice?)", "/** Lays the info ring in the place")
        assertTrue(set, "VolumeCardInk.render(notice)" in set && "redrawSoon()" in set && "if (notice == volumeCard) return@onGlThread" in set)
        assertTrue("its own texture", "volumeTexture = ids[6]" in gl && "GLES20.glGenTextures(7, ids, 0)" in gl)
        assertTrue("laid at the layout's place", "drawQuad(volumeTexture, volumeW, volumeH, VolumeCardLayout.X, VolumeCardLayout.Y)" in gl)
    }
}
