package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the compositor (it needs OpenGL) and the service (it needs Android) do with the [com.reau.app.video.LookGate] is read from the source, as
 * [DiagnosticLinesAreWiredTest] does for the log lines; what the gate and the screen watch do with it is in [com.reau.app.aa.AaScreenReconnectTest].
 */
class LookGateWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    private val compositor = source("video/DashCompositor.kt")
    private val sides = source("service/LiveSides.kt")

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `a look is taken only when the gate is open`() {
        assertTrue(compositor.between("private fun takeLook()", "val now = SystemClock.uptimeMillis()").contains("!lookGate.open"))
    }

    @Test
    fun `the picture that arrives opens the gate before its look is taken`() {
        val latch = compositor.between("waitClock.arrived(System.nanoTime())", "InputLatency.pictureArrived()")
        assertTrue(latch.contains("lookGate.pictureArrived()"))
        assertTrue("opened before the look", latch.indexOf("lookGate.pictureArrived()") < latch.indexOf("takeLook()"))
    }

    @Test
    fun `holding the gate is done on the compositor's thread and waited for`() {
        assertTrue(compositor.between("fun awaitNewPicture()", "\n").let { it.contains("onGlThread(wait = true)") && it.contains("lookGate.awaitPicture()") })
    }

    @Test
    fun `a projection holds the gate, then forgets the watch, then starts Android Auto`() {
        val start = sides.between("override fun startAndroidAuto()", "override fun stopAndroidAuto()")
        val hold = start.indexOf("compositor.awaitNewPicture()")
        val forget = start.indexOf("AaScreens.projectionStarting()")
        val go = start.indexOf("running.start()")
        assertTrue("all three are there: $hold $forget $go", hold >= 0 && forget >= 0 && go >= 0)
        assertTrue("held, then forgotten, then started: $hold $forget $go", hold < forget && forget < go)
    }

    @Test
    fun `the end of a projection still resets the watch`() {
        assertTrue(sides.between("override fun stopAndroidAuto()", "AaDialNow.stopped(dialNumber)").contains("AaScreens.projection(false)"))
    }
}
