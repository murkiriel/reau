package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The action echo and the joystick's legend (P30 Eco): what decides them is tested in TimedShowTest, DashSessionEchoTest, JoystickEchoTest and
 * JoystickLegendTest. Where they are fed from needs the live sides (a Context, the dash session, the compositor's GL thread), so those lines are
 * read from the source, as the other "...IsWired" tests do.
 */
class EchoAndLegendAreWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val session = File(main, "dash/DashSession.kt").readText()
    private val compositor = File(main, "video/DashCompositor.kt").readText()
    private val gl = File(main, "video/CompositorGl.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the tap is sent first, then its name and the legend are said, and the log line comes after`() {
        val tap = live.between("session.onButton = { event ->", "showButton(com.reau.app.video.ButtonOverlay.textFor(")
        val send = tap.indexOf("send(press.sent)")
        val say = tap.indexOf("echoAndLegend(press, joystick, context, volumeEcho)")
        val log = tap.indexOf("RideDiagnostics.log(\"dash\", JoystickMap.logLine(")
        assertTrue(tap, send in 0 until say && say < log)
    }

    @Test
    fun `the echo goes to the session's title and the legend to the compositor, each with its line in the diagnostic log`() {
        val say = live.between("private fun echoAndLegend(", "/** What the maneuver ring last did")
        assertTrue(say, "val text = volumeText ?: com.reau.app.dash.ActionEchoText.text(words, label)" in say && "session.echoAction(text)" in say)
        assertTrue(say, "RideDiagnostics.log(\"dash\", \"action echo: \${label.name}\")" in say)
        assertTrue(say, "map.legend(mode)" in say && "legend.show(card)" in say)
        assertTrue(say, "RideDiagnostics.log(\"dash\", \"joystick legend: " in say)
        assertTrue("the legend change goes to the compositor", "private fun legendChanged() {\n        compositor.setLegend(legend.current())\n    }" in live)
    }

    @Test
    fun `the legend lasts three seconds, ends by itself in the sides' scope and is declared before the init block that uses it`() {
        val declaration = live.between("private val legend: com.reau.app.dash.TimedShow<com.reau.app.dash.LegendCard> = com.reau.app.dash.TimedShow(", "private fun legendChanged()")
        assertTrue(declaration, "com.reau.app.dash.ActionLegend.SHOW_MS" in declaration)
        assertTrue(declaration, "val job = scope.launch {\n                delay(delayMs)\n                action()" in declaration)
        assertTrue(declaration, "changed = ::legendChanged" in declaration)
        assertTrue("before the init block", live.indexOf("private val legend: ") < live.indexOf("\n    init {"))
    }

    @Test
    fun `both switches are taken when the projection starts and at each change of the settings`() {
        assertTrue(live, "session.actionEcho = setup.settings.actionEcho\n            legend.enabled = setup.settings.joystickLegend" in live)
        val retune = live.between("if (LiveTuning.Step.JOYSTICK in steps) {", "if (LiveTuning.Step.STATS_OVERLAY in steps)")
        assertTrue(retune, "session.actionEcho = settings.actionEcho" in retune && "legend.enabled = settings.joystickLegend" in retune)
    }

    @Test
    fun `the session sends the card at once when the title changes and when it comes back, and forgets the echo when the session ends`() {
        assertTrue(session, "changed = ::sendTitleNow" in session)
        val send = session.between("private fun sendTitleNow() {", "/** The phone's own decimal separator")
        assertTrue(send, "_state.value == DashState.STREAMING" in send && "sendRouteCard(sock)" in send)
        // The card goes through the route hold (the rider's yes to "Exit navigation?" keeps it back), and is what the route card is made of.
        val card = session.between("private fun sendRouteCard(sock: DashSocket) {", "/**")
        assertTrue(card, "routeHold.sendUnlessHeld { sock.send(liveRouteCard(projectionOn = true)) }" in card)
        assertTrue("disconnect", session.between("fun disconnect", "socket?.let { sock ->").contains("echo.drop()"))
        assertTrue("fail", session.between("statsJob?.cancel(); cardsJob?.cancel(); echo.drop()", "sessionJob?.cancel()").isNotEmpty())
    }

    @Test
    fun `the title of the card is the echo first, in the route and in the idle card`() {
        val card = session.between("private fun liveRouteCard(projectionOn: Boolean): ByteArray {", "@Volatile internal var echo")
        assertTrue(card, "val echoNow = echo.current()" in card && "val echoed = echoNow ?: infoTitle.page(" in card)
        assertTrue(card, "echoed ?: n.road.ifBlank { destinationName }" in card)
        assertTrue(card, "echoed ?: destinationName" in card)
    }

    @Test
    fun `the compositor draws the legend over the ring and under the stuck-picture badge, and redraws at once when it changes`() {
        val draw = compositor.between("private fun drawNotices() {", "private fun drawAndPost(")
        assertTrue(draw, draw.indexOf("if (notices.legend) gl.drawLegend()") < draw.indexOf("if (staleBadge != null) gl.drawBadge()"))
        val set = compositor.between("fun setLegend(card: com.reau.app.dash.LegendCard?)", "/** Lays the info ring in the place")
        assertTrue(set, "LegendInk.render(card)" in set && "redrawSoon()" in set && "if (card == legend) return@onGlThread" in set)
        assertTrue("its own texture", "legendTexture = ids[4]" in gl && "GLES20.glGenTextures(8, ids, 0)" in gl)
    }
}
