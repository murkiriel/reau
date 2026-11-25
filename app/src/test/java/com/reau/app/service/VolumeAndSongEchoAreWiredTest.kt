package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The joystick's volume actions and the song's name in the title (P32 Joy): what decides them is tested in VolumeActionsTest, PhoneVolumeTest, SongEchoTest and
 * DashSessionSongEchoTest. Where they are fed from needs the live sides (a Context, the AudioManager, the dash session's socket), so those lines are read from the
 * source, as the other "...IsWired" tests do.
 */
class VolumeAndSongEchoAreWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val session = File(main, "dash/DashSession.kt").readText()
    private val receiver = File(main, "aa/AaReceiver.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `a volume command is the phone's own, LiveSides takes it, Android Auto's input refuses it`() {
        val send = live.between("val send: (List<AaCommand>) -> Unit = { commands ->", "// Debug only: the maneuver ring")
        assertTrue(send, "is AaCommand.Volume -> volumeStep(command.step, context)" in send)
        assertTrue(receiver, "is AaCommand.Volume, is AaCommand.PanelZoom" in receiver)
    }

    @Test
    fun `the step asks the phone's volume with the call state, logs the levels, says the level in the title and sends the status at once`() {
        val step = live.between("private fun volumeStep(", "/** What the maneuver ring last did")
        assertTrue(step, "setup.volume?.step(step, setup.calls.state())" in step)
        assertTrue(step, "RideDiagnostics.log(\"projection\", change.logLine(step))" in step)
        assertTrue(step, "volumeEcho = com.reau.app.dash.VolumeCards.titleEcho(volumeCard.enabled) { com.reau.app.dash.ActionEchoText.volume(words, change) }" in step)
        assertTrue(step, "session.sendStatusNow()" in step)
    }

    @Test
    fun `the real phone has its volume, and the tap's echo is cleared before it is sent and read after`() {
        assertTrue(live, "volume = com.reau.app.phone.PhoneVolume(com.reau.app.phone.AudioVolume(context))," in live)
        val tap = live.between("session.onButton = { event ->", "showButton(com.reau.app.video.ButtonOverlay.textFor(")
        assertTrue(tap, tap.indexOf("volumeEcho = null") in 0 until tap.indexOf("send(press.sent)"))
        assertTrue(tap, tap.indexOf("send(press.sent)") < tap.indexOf("echoAndLegend(press, joystick, context, volumeEcho)"))
    }

    @Test
    fun `the tap of a song change holds its name in the title through the session's own call`() {
        val say = live.between("private fun echoAndLegend(", "/** The title's words for the volume step")
        assertTrue(say, "if (label.changesSong) session.echoSongAction(text) else session.echoAction(text)" in say)
        val hold = session.between("fun echoSongAction(", "/** The route card sent now")
        assertTrue(hold, "echo.show(said, maxOf(ActionEcho.SHOW_MS, songEcho.remainingMs()))" in hold)
        val asked = session.between("fun songChangeAsked() {", "// The text of the song change's action name")
        assertTrue(asked, "echo.show(label, songEcho.remainingMs())" in asked)
    }

    @Test
    fun `the song keys open the wait for the name before they are sent`() {
        val key = live.between("is AaCommand.Key -> {", "else -> receiver?.sendInput(command)")
        assertTrue(key, key.indexOf("if (JoystickMap.isSongKey(command.keycode)) session.songChangeAsked()") in 0 until key.indexOf("receiver?.sendInput(command)"))
    }

    @Test
    fun `the session takes the new song's name from the card's update, and sends the card and the status at once`() {
        val update = session.between("fun updateNowPlaying(", "// What the music card says now")
        assertTrue(update, "songEcho.onSong(title)?.let(::echoSong)" in update)
        assertTrue(update, "songNow = title" in update)
        assertTrue(update, "if (cardChanged) sendCardsNow()" in update)
        val status = session.between("fun sendStatusNow() {", "/** The route card sent now")
        assertTrue(status, "_state.value != DashState.STREAMING" in status && "DashCommands.heartbeat(weather = weatherProvider(), status = phoneStatusProvider(), call = callNow())" in status)
        val song = session.between("fun songChangeAsked() {", "/** [name] (cut and in the dash's letters already)")
        assertTrue(song, "if (callerName == null) songEcho.asked(songNow)" in song)
        assertTrue("the wait ends with the session", session.between("fun disconnect", "socket?.let { sock ->").contains("songEcho.cancel()"))
    }
}
