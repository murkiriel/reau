package com.reau.app.aa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lines that carry the net's app and Android Auto's playback status from the classes that need Android (the compositor, the live service, the
 * receiver's session) to the watchers, read from the source as [com.reau.app.service.DiagnosticLinesAreWiredTest] does. What the watchers do with
 * them is covered by [AaAppTest] and [AaMediaTest].
 */
class AppAndMediaAreWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the compositor's look tells the watch which app the net called the picture`() {
        val live = source("service/LiveSides.kt")
        val listener = live.between("compositor.onLook = {", "// What the joystick asked for")
        assertTrue(listener, listener.contains("AaScreens.watch.look("))
        assertTrue(listener, listener.contains("com.reau.app.aa.AaApp.of(compositor.lookedScreen)"))
    }

    @Test
    fun `the compositor hands out the class of the look it has just taken`() {
        val compositor = source("video/DashCompositor.kt")
        assertTrue(compositor.contains("val lookedScreen: ScreenNet.Screen get() = lookResult.screen"))
        assertTrue("the look fills the same result", compositor.contains("PictureLook.of(it, PictureLook.SAMPLE_W, PictureLook.SAMPLE_H, lookResult)"))
    }

    @Test
    fun `the receiver gives the cards the process's media watch, and clears it when the session ends`() {
        val receiver = source("aa/AaReceiver.kt")
        assertTrue(receiver.contains("AapCards(onNowPlaying, onCaller, onMediaApp, AaMedia.watch::playback)"))
        val ending = receiver.between("onCaller?.invoke(null)", "recorder?.close()")
        assertTrue(ending, ending.contains("AaMedia.watch.reset()"))
    }
}
