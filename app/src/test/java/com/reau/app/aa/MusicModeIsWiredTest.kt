package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lines that put the music mode on the projection and on the joystick page, from the classes that need Android (the live service, the
 * screen), read from the source as [AppAndMediaAreWiredTest] does. What the mode and the held side do is [MusicModeTest] and [JoystickMusicTest]; the
 * warning's rule is [GhostSideWarningTest].
 */
class MusicModeIsWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the projection feeds the music mode with the screen, the app, the media status and the wait for the picture`() {
        val feed = source("service/LiveSides.kt").between("val music = com.reau.app.aa.MusicMode", "// What the joystick asked for")
        assertTrue(feed, feed.contains("joystick.musicActive = music::active"))
        val sources = feed.between("combine(", ") { screen, app, media, pending ->").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            feed,
            listOf(
                "com.reau.app.aa.AaScreens.watch.screen", "com.reau.app.aa.AaScreens.watch.app",
                "com.reau.app.aa.AaMedia.watch.now", "com.reau.app.aa.AaScreens.watch.appsPending",
            ),
            sources,
        )
        assertTrue(feed, feed.contains("music.update(screen, app, media, com.reau.app.util.MonotonicClock.nowMs(), pending)"))
    }

    @Test
    fun `the music card is told when Spotify is in front and playing`() {
        val feed = source("service/LiveSides.kt").between("val music = com.reau.app.aa.MusicMode", "// What the joystick asked for")
        assertTrue(feed, feed.contains("session.musicModePlaying = music.playing(com.reau.app.util.MonotonicClock.nowMs())"))
        val card = source("dash/DashSession.kt").between("val music = nowPlayingPackets.takeIf {", "}")
        assertTrue(card, card.contains("musicCardAllowed || musicModePlaying"))
    }

    @Test
    fun `the mode's switchings go to the ride log`() {
        val feed = source("service/LiveSides.kt").between("val music = com.reau.app.aa.MusicMode", "joystick.musicActive")
        assertTrue(feed, feed.contains("RideDiagnostics.log(\"dash\", it)"))
    }

    @Test
    fun `the end of a pause is told when it comes`() {
        val feed = source("service/LiveSides.kt").between("val music = com.reau.app.aa.MusicMode", "// What the joystick asked for")
        assertTrue(feed, feed.contains("music.expiresAtMs()"))
        assertTrue(feed, feed.contains("music.active(com.reau.app.util.MonotonicClock.nowMs())"))
    }

    @Test
    fun `a button holds a side and a job sends it when its time comes`() {
        val live = source("service/LiveSides.kt")
        val job = live.between("val releaseHeldSide: () -> Unit = {", "session.onButton = { event ->")
        assertTrue(job, job.contains("joystick.heldDueMs()"))
        assertTrue(job, job.contains("joystick.release(com.reau.app.util.MonotonicClock.nowMs())"))
        assertTrue(job, job.contains("send(press.sent)"))
        assertTrue(job, job.contains("RideDiagnostics.log(\"dash\", \"button held side -> "))
        val button = live.between("session.onButton = { event ->", "// Behind its switch, and only with Android Auto told of a touch screen.")
        assertTrue(button, button.contains("releaseHeldSide()"))
        assertTrue("the job is asked after the press is sent", button.indexOf("send(press.sent)") < button.indexOf("releaseHeldSide()"))
    }

    @Test
    fun `the joystick page shows the warning under the action`() {
        val screen = source("ui/JoystickScreen.kt")
        assertTrue(screen.contains("GhostSideWarning.warns(slot, action)"))
        assertTrue(screen.contains("stringResource(R.string.joystick_ghost_side)"))
        assertTrue(screen.contains("testTag(\"joystick_ghost_warning\")"))
    }

    @Test
    fun `the warning is in the three languages`() {
        val texts = listOf("values", "values-pt", "values-es").map { dir ->
            File("src/main/res/$dir/strings.xml").readText().between("<string name=\"joystick_ghost_side\">", "</string>")
        }
        assertEquals(3, texts.toSet().size)
        assertEquals("This side sometimes fires together with the center.", texts[0])
        assertEquals("Este lado às vezes dispara junto do centro.", texts[1])
    }
}
