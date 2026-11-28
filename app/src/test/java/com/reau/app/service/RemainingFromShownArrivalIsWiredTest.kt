package com.reau.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review of 26/11/2026 (A1): with no seconds from Android Auto (Waze) the time left on the dash comes from the arrival time the dash SHOWS, not from the raw text that flickered by
 * a minute. The rule is tested through the session in DashSessionArrivalTest; that the guidance reaches the session in that form needs the service, so the source is read.
 */
class RemainingFromShownArrivalIsWiredTest {
    private val main = File("src/main/java/com/reau/app")

    @Test
    fun `the guidance gives the session the seconds only when Android Auto said them, and the phone's clock to count from the arrival shown`() {
        val live = File(main, "service/LiveSides.kt").readText()
        val show = live.substringAfter("private fun DashSession.showGuidance(").substringBefore("A call answered or ended by the joystick")
        assertTrue(show, "guidance.timeToArrivalSeconds.takeIf { it >= 0 } ?: com.reau.app.dash.protocol.DashCommands.REMAINING_OMIT, nav.next," in show)
        assertTrue(show, "clock = java.time.LocalTime.now()," in show)
        assertTrue("the raw derived figure is not handed on any more", "nav.remainingSeconds" !in show)
    }

    @Test
    fun `the session counts the time left from the arrival it shows, after the smoothing`() {
        val session = File(main, "dash/DashSession.kt").readText().substringAfter("fun updateNavInfo(").substringBefore("/** What the time left and the arrival time of the guidance")
        assertTrue(session, "val shownEta = arrival.arrival(etaHHMM, nowMs)" in session)
        assertTrue(session, "AaManeuvers.remainingFromEta(shownEta.orEmpty(), it)" in session)
        assertTrue("never from the raw text", "remainingFromEta(etaHHMM" !in session)
    }
}
