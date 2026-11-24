package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rider's notices for a dash that does not answer (ride log of 23/11/2026 08:05: 86 s of silence on a joined network). A silent dash is told after
 * three sessions in a row that end with no word from it while the phone's network to it is joined and the dash had spoken before in the projection;
 * the ordinary ignition (the Wi-Fi goes away) and a dash still starting up are not it. And the notice that another app holds the dash's port comes once
 * for an episode, not at every try.
 */
class SilentDashTest {
    private val told = mutableListOf<Boolean>()
    private val silent = SilentDash().also { it.notice = { tell -> told += tell } }

    private fun silentEnd(joined: Boolean = true, spoke: Boolean = true) = silent.silentEnd(wifiJoined = joined, dashSpoke = spoke)

    // ── The silent dash ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the third silent session in a row with the Wi-Fi joined tells the rider, the first two do not`() {
        assertEquals(SilentDash.Verdict.NONE, silentEnd())
        assertEquals(SilentDash.Verdict.NONE, silentEnd())
        assertTrue("nothing told yet", told.isEmpty())
        assertEquals(SilentDash.Verdict.TELL, silentEnd())
        assertEquals(listOf(true), told)
        assertEquals(3, SilentDash.TELL_AT)
    }

    @Test
    fun `an episode is told once, not at every session after the third`() {
        repeat(3) { silentEnd() }
        repeat(4) { assertEquals(SilentDash.Verdict.NONE, silentEnd()) }
        assertEquals(listOf(true), told)
    }

    @Test
    fun `the ordinary ignition, the dash's Wi-Fi gone when the session ends, never counts`() {
        repeat(10) { assertEquals(SilentDash.Verdict.NONE, silentEnd(joined = false)) }
        assertTrue(told.isEmpty())
        assertEquals(0, silent.silentRun())
    }

    @Test
    fun `a dash that has not spoken in this projection, still starting up, never counts`() {
        repeat(10) { assertEquals(SilentDash.Verdict.NONE, silentEnd(spoke = false)) }
        assertTrue(told.isEmpty())
    }

    @Test
    fun `a session that ends with the network gone breaks the run, the count starts again`() {
        silentEnd()
        silentEnd()
        silentEnd(joined = false)
        assertEquals(0, silent.silentRun())
        silentEnd()
        assertEquals("two more are not three in a row", SilentDash.Verdict.NONE, silentEnd())
        assertTrue(told.isEmpty())
    }

    @Test
    fun `reaching STREAMING, or the network falling, ends the run and takes the notice away`() {
        repeat(3) { silentEnd() }
        silent.reset()
        assertEquals(listOf(true, false), told)
        assertEquals(0, silent.silentRun())
        // A new episode is told again.
        repeat(3) { silentEnd() }
        assertEquals(listOf(true, false, true), told)
    }

    @Test
    fun `a reset with no notice up says nothing`() {
        silentEnd()
        silent.reset()
        silent.reset()
        assertTrue(told.isEmpty())
    }

    @Test
    fun `the log line says the silence, the joined Wi-Fi and the count, with no name`() {
        assertEquals(
            "the dash has been silent 86 s while its Wi-Fi is still joined (session 2 in a row; the rider is told at 3)",
            SilentDash.line(86, 2),
        )
    }

    // ── Another app holds the port ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the port taken is told at the first try, and not at the retries 2 4 8 16 and 30 s apart`() {
        val notice = TakenNotice()
        assertEquals(TakenNotice.Edge.SHOW, notice.link(taken = true, ready = false))
        // The machine goes to CONNECTING and back to TAKEN at every retry: the other links leave the notice alone.
        repeat(5) {
            assertEquals(TakenNotice.Edge.NONE, notice.link(taken = false, ready = false))
            assertEquals(TakenNotice.Edge.NONE, notice.link(taken = true, ready = false))
        }
    }

    @Test
    fun `when REau connects the notice goes, and a later taking is a new episode`() {
        val notice = TakenNotice()
        notice.link(taken = true, ready = false)
        assertEquals(TakenNotice.Edge.CLEAR, notice.link(taken = false, ready = true))
        assertEquals("ready again: nothing to take away", TakenNotice.Edge.NONE, notice.link(taken = false, ready = true))
        assertEquals(TakenNotice.Edge.SHOW, notice.link(taken = true, ready = false))
    }

    @Test
    fun `a dash that connects without having been taken asks for nothing`() {
        assertEquals(TakenNotice.Edge.NONE, TakenNotice().link(taken = false, ready = true))
    }

    @Test
    fun `the end of the projection takes a notice away once, and only if one is up`() {
        val notice = TakenNotice()
        assertFalse(notice.end())
        notice.link(taken = true, ready = false)
        assertTrue(notice.end())
        assertFalse(notice.end())
        assertEquals("told again in the next projection", TakenNotice.Edge.SHOW, notice.link(taken = true, ready = false))
    }

    // ── In the session ──────────────────────────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun session(networkUp: Boolean) = DashSession(scope, networkUp = { networkUp }, silentDash = silent)

    @Test
    fun `the session counts a silent end only with the network joined, and tells at the third`() {
        val s = session(networkUp = true)
        s.noteSilentEnd(30, dashSpoke = true, generation = 0)
        s.noteSilentEnd(30, dashSpoke = true, generation = 0)
        assertTrue(told.isEmpty())
        s.noteSilentEnd(30, dashSpoke = true, generation = 0)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a session whose network is gone counts nothing`() {
        val gone = session(networkUp = false)
        repeat(5) { gone.noteSilentEnd(30, dashSpoke = true, generation = 0) }
        assertEquals(0, silent.silentRun())
        assertTrue(told.isEmpty())
    }

    @Test
    fun `a failure that is not a silent one ends the run, a silent one noted just before it does not`() {
        val other = session(networkUp = true)
        silentEnd()
        silentEnd()
        other.fail("Lost connection to dash — socket error: Socket closed")
        assertEquals("another kind of end: the run starts over", 0, silent.silentRun())

        val s = session(networkUp = true)
        s.noteSilentEnd(30, dashSpoke = true, generation = 0)
        s.fail("Dash stopped responding — connection lost")
        assertEquals("the silent end keeps its place in the run", 1, silent.silentRun())
    }

    // ── The wiring, read from the source ────────────────────────────────────────────────────────────────

    private val dir = "src/main/java/com/reau/app/"
    private val sessionSource = File(dir + "dash/DashSession.kt").readText()

    @Test
    fun `the RX watchdog and the auth wait with no packet note the silent end before they fail the session`() {
        val rx = sessionSource.substringAfter("if (rxSilenceFails(_state.value").substringBefore("break")
        assertTrue(rx.indexOf("noteSilentEnd(silent, dashSpoke = true, mine)") in 0 until rx.indexOf("fail(\"Dash stopped responding"))
        val auth = sessionSource.substringAfter("if (!authConfirmed) {").substringBefore("authTimeoutReason(")
        assertTrue(auth.contains("noteSilentAuthEnd(mine)"))
        val silentAuth = sessionSource.substringAfter("private fun noteSilentAuthEnd(mine: Int)").substringBefore("\n    }\n")
        assertTrue("only with not a single packet", silentAuth.contains("if (lastRxMs != 0L) return"))
        assertTrue("the dash must have spoken before in this projection", silentAuth.contains("dashSpoke = DashHeard.agoMs(now) != null"))
    }

    @Test
    fun `streaming, a new attempt and a loss of the network end the run`() {
        assertTrue(sessionSource.substringAfter("fun startStreaming()").substringBefore("TimeToPicture").contains("silentDash.reset()"))
        val wifi = File(dir + "dash/DashWifiManager.kt").readText()
        assertTrue(wifi.substringAfter("fun connect(ssid: String").substringBefore("requestNetwork()").contains("SilentDash.shared.reset()"))
        assertTrue(wifi.substringAfter("private fun bikeLost()").substringBefore("scheduleReconnect()").contains("SilentDash.shared.reset()"))
    }

    @Test
    fun `the service tells in the alert channel and takes the notices away with the projection`() {
        val service = File(dir + "service/ProjectionService.kt").readText()
        assertTrue(service.contains("ProjectionNotification.showDashSilent(this)"))
        assertTrue(service.contains("ProjectionNotification.clearDashSilent(this)"))
        assertTrue(service.contains("ProjectionNotification.showDashTaken(this)"))
        val release = service.substringAfter("override fun release() {").substringBefore("deviceLook = null")
        assertTrue(release.contains("SilentDash.shared.reset()"))
        assertTrue(release.contains("if (taken.end()) ProjectionNotification.clearDashTaken(this@ProjectionService)"))
        val notification = File(dir + "service/ProjectionNotification.kt").readText()
        for (shown in listOf("ID_DASH_TAKEN, R.string.notif_title_dash_taken, R.string.notif_text_dash_taken_alert", "ID_DASH_SILENT, R.string.notif_title_dash_silent, R.string.notif_text_dash_silent")) {
            assertTrue(shown, notification.contains(shown))
        }
        assertTrue("the alert channel, with sound", notification.substringAfter("private fun showAlert(").contains("Notification.Builder(context, ALERT_CHANNEL_ID)"))
    }
}
