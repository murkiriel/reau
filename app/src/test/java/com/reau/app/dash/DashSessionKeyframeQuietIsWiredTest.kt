package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The session's loops need a socket and a scope, so, as [com.reau.app.service.DashRecoveryIsWiredTest] does, the lines that wire [KeyframeQuiet] are
 * read from the source: the routine sends wait for the keyframe's burst to pass, what the rider or the dash asked for does not.
 */
class DashSessionKeyframeQuietIsWiredTest {
    private val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `the four routine loops wait for the keyframe before they build and send, and take the wait from their delay`() {
        val loops = listOf(
            session.between("private fun launchStatusHeartbeat(", "private fun rtpStallFails("),
            session.between("private fun launchProjectionHeartbeat()", "private fun launchRouteCardKeepAlive()"),
            session.between("private fun launchRouteCardKeepAlive()", "private fun launchNavInfo()"),
            session.between("private fun launchNavInfo()", "internal fun navPacketNow()"),
        )
        loops.forEach { loop ->
            assertEquals(loop, 1, Regex("""quietBeforeRoutine\(\)""").findAll(loop).count())
            assertTrue(loop, loop.contains("- slid)"))
        }
        // The heartbeat and the clock (06 06) are built after the wait, which is the first line of the tick.
        val tick = loops[0].substringAfter("while (isActive) {")
        assertTrue(tick.indexOf("quietBeforeRoutine()") < tick.indexOf("phoneStatusProvider()"))
        assertTrue(tick.indexOf("quietBeforeRoutine()") < tick.indexOf("DashCommands.timeSync()"))
    }

    @Test
    fun `what is not routine goes out at once`() {
        assertFalse(session.between("fun sendStatusNow()", "The route card sent now").contains("quietBeforeRoutine"))
        assertFalse(session.between("private fun launchCards()", "Internal (not private)").contains("quietBeforeRoutine"))
        // quietBeforeRoutine is called in exactly the four loops (and defined once).
        assertEquals(5, Regex("""quietBeforeRoutine\(""").findAll(session).count())
        assertFalse(session.contains("QuietBeforeRoutine"))
    }

    @Test
    fun `the video tells the session when a keyframe starts and ends`() {
        assertTrue(live.contains("override fun keyframeStarting() = session.keyframeStarting()"))
        assertTrue(File("src/main/java/com/reau/app/stream/VideoLoop.kt").readText().contains("onKeyframeStart = dash::keyframeStarting"))
        assertTrue(session.between("fun keyframeSent(", "fun keyframeStarting()").contains("keyframeQuiet.keyframeEnded("))
    }
}
