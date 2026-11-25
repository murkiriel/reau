package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The call card on the dash's video: what decides it is tested in CallCardsTest, CallCardLayoutTest and JoystickCallCardTest. Where it is fed from needs the live sides
 * (a Context, Android Auto's phone channel, the compositor's GL thread), so those lines are read from the source, as the other "...IsWired" tests do.
 */
class CallCardIsWiredTest {
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

    private val feed = File(main, "service/CallCardFeed.kt").readText()

    @Test
    fun `Android Auto's word of the call feeds the dash session as before and the card too, with the private-number word for a call with no name`() {
        val caller = live.between("options = options.copy(onCaller = { caller ->", "RideDiagnostics.log(\"call\"")
        assertTrue(caller, "session.showCallerCard(caller, context.getString(R.string.caller_private))" in caller)
        assertTrue(caller, "callCard.heard(caller, context.getString(R.string.caller_private))" in caller)
        val heard = feed.between("fun heard(", "/** The switch:")
        assertTrue(heard, "tracker.onNoCall()" in heard && "tracker.onCall(caller.name.ifEmpty { privateName }, caller.inCall, numberOnly = caller.numberOnly)" in heard)
        assertTrue("the phase goes to the log only with the card on", "if (changed && on)" in heard)
        assertTrue(heard, "log(CallCards.logLine(it))" in heard)
    }

    @Test
    fun `the feed is made with the compositor, the words of the app's language and the ride log`() {
        val made = live.between("private val callCard = CallCardFeed(", "private fun callCardSwitch(")
        assertTrue(made, "compositor::setCallCard" in made)
        assertTrue(made, "com.reau.app.dash.ActionEchoText.callWords(appContext)" in made)
        assertTrue(made, "RideDiagnostics.log(\"dash\", it)" in made)
    }

    @Test
    fun `the card goes to the compositor only with the switch on, and again at its next change by itself while a call goes on`() {
        val refresh = feed.between("private fun refresh() {", "\n}\n")
        assertTrue(refresh, "tracker.current()?.takeIf { on }" in refresh)
        assertTrue(refresh, "show(card?.let { CallCards.notice(it, words()) })" in refresh)
        assertTrue(refresh, "tracker.nextTickMs()" in refresh)
        assertTrue(refresh, "delay(wait)\n                refresh()" in refresh)
        assertTrue(refresh, "tick?.cancel()" in refresh)
        assertTrue("the switch turned on in the middle of a call says the phase", "if (wanted) tracker.current()?.let { log(CallCards.logLine(it)) }" in feed)
    }

    @Test
    fun `the switch turns on the session's silence and the joystick's keys, and is taken at the start and at each change of the settings`() {
        val switch = live.between("private fun callCardSwitch(on: Boolean) {", "// The map's zoom from the joystick")
        assertTrue(switch, "if (!callCard.switch(on)) return" in switch)
        assertTrue(switch, "session.callCardOn = on" in switch)
        assertTrue(switch, "joystick?.callCard = on" in switch)
        assertTrue(live, "callCardSwitch(setup.settings.callCard)" in live)
        val retune = live.between("if (LiveTuning.Step.DASH_EXTRAS in steps) {", "if (LiveTuning.Step.DASH_EXTRAS in steps && settings.weatherWanted")
        assertTrue(retune, "callCardSwitch(settings.callCard)" in retune)
    }

    @Test
    fun `the session sends the dash neither the call's state nor its name with the card on, and clears what it sent once`() {
        val round = session.between("private fun sendCardsHeld(sock: DashSocket): List<ByteArray>? {", "val music = nowPlayingPackets")
        assertTrue(round, "val caller = callerName?.takeUnless { callCardOn }" in round)
        assertTrue("the clear goes only if the round had sent the call", "} else if (callCardShown) {" in round)
        assertTrue("the 1 Hz status says no call", "callerName == null || callCardOn -> DashCommands.CallState.NONE" in session)
        assertTrue("the switch applies at once", "sendCardsNow()" in session.substringAfter("@Volatile var callCardOn = false").take(200))
    }

    @Test
    fun `the compositor lays the call card by the overlay rules, over the info ring and under the stuck-picture badge, with its own texture`() {
        val draw = compositor.between("private fun drawNotices() {", "private fun drawAndPost(")
        assertTrue(draw, "volume = volumeCard != null, call = callCard != null, legend = legend != null" in draw)
        assertTrue(draw, draw.indexOf("if (infoRing != null) gl.drawRing()") < draw.indexOf("if (notices.legend) gl.drawLegend()"))
        assertTrue(draw, draw.indexOf("if (notices.legend) gl.drawLegend()") < draw.indexOf("if (notices.volume) gl.drawVolumeCard()"))
        assertTrue(draw, draw.indexOf("if (notices.volume) gl.drawVolumeCard()") < draw.indexOf("if (notices.call) gl.drawCallCard()"))
        assertTrue(draw, draw.indexOf("if (notices.call) gl.drawCallCard()") < draw.indexOf("if (staleBadge != null) gl.drawBadge()"))
        val frame = compositor.between("private fun drawAndPost(", "val clock = CompositorRules.advance(")
        assertTrue("the notices go on the picture, not on the fps test card", frame.substringAfter("} else {").contains("drawNotices()"))
        val set = compositor.between("fun setCallCard(notice: com.reau.app.dash.CallNotice?)", "/** Lays the info ring in the place")
        assertTrue(set, "CallCardInk.render(notice)" in set && "redrawSoon()" in set && "if (notice == callCard) return@onGlThread" in set)
        assertTrue("its own texture", "callTexture = ids[7]" in gl && "GLES20.glGenTextures(8, ids, 0)" in gl)
        assertTrue("laid at the layout's place", "drawQuad(callTexture, callW, callH, CallCardLayout.X, CallCardLayout.Y)" in gl)
    }

    @Test
    fun `the joystick's end of a ringing call tells the card the rider declined it, so it is no missed call`() {
        val send = live.between("AaCommand.AnswerCall, AaCommand.EndCall -> {", "is AaCommand.CallVolume")
        assertTrue(send, "if (command == AaCommand.EndCall) callCard.declinedByRider()" in send)
        assertTrue("told before the keys go", send.indexOf("callCard.declinedByRider()") < send.indexOf("callByKeys(command, receiver, setup.calls)"))
        assertTrue(feed, "fun declinedByRider() = tracker.onDeclined()" in feed)
    }

    @Test
    fun `the log never says a name or a number of a call`() {
        assertFalse("the feed's log calls say only the phase", feed.contains("log(\"") || feed.contains("caller.name}"))
        assertTrue(feed.contains("log(CallCards.logLine(it))"))
        assertTrue(File(main, "dash/CallCards.kt").readText().contains("\"call card: ended after \${clock(card.seconds)}\""))
        assertTrue(File(main, "dash/CallCards.kt").readText().contains("\"call card: missed\""))
    }
}
