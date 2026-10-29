package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.FinishStop
import com.reau.app.service.ProjectionEffect.NotifyNoDash
import com.reau.app.service.ProjectionEffect.Release
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEffect.StopAndroidAuto
import com.reau.app.service.ProjectionEffect.StopDash
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.BikeNetworkAvailable
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bike switched off and forgotten. Observed in the ride logs of 19/10/2026: with the dash's Wi-Fi gone the phone kept
 * Android Auto, the decoder and its locks, asking for the Wi-Fi every 14 s, until the rider tapped Desligar. Now the
 * projection stops by itself once the dash has been gone for [ProjectionMachine.NO_DASH_STOP_MS].
 */
class ProjectionMachineNoDashStopTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent, nowMs: Long = 0L, limitMs: Long = ProjectionMachine.NO_DASH_STOP_MS): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event, nowMs, limitMs)
        state = step.state
        return step.effects
    }

    private fun runningWithDashGoneAt(goneAtMs: Long) {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.READY), goneAtMs - 1_000L)
        on(DashChanged(DashLink.DROPPED), goneAtMs)
    }

    @Test
    fun `a dash gone for twenty minutes stops the projection at the next retry, as Desligar would, and says so`() {
        runningWithDashGoneAt(0L)
        val effects = on(DashRetryDue, ProjectionMachine.NO_DASH_STOP_MS)
        assertEquals(listOf(NotifyNoDash(ProjectionMachine.NO_DASH_STOP_MS), StopAndroidAuto, StopDash, Release, FinishStop), effects)
        assertEquals(Phase.STOPPED, state.phase)
        assertTrue(state.stopping)
    }

    @Test
    fun `one millisecond short of twenty minutes the dash is still tried again`() {
        runningWithDashGoneAt(0L)
        assertEquals(listOf(StartDash), on(DashRetryDue, ProjectionMachine.NO_DASH_STOP_MS - 1))
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `the twenty minutes count from when the dash went, not from the start of the projection`() {
        val goneAt = 3 * 60 * 60_000L
        runningWithDashGoneAt(goneAt)
        assertEquals(listOf(StartDash), on(DashRetryDue, goneAt + 5 * 60_000L))
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `the count starts over when the dash comes back`() {
        runningWithDashGoneAt(0L)
        on(DashRetryDue, 60_000L)
        on(DashChanged(DashLink.READY), 70_000L)
        on(DashChanged(DashLink.DROPPED), 15 * 60_000L)
        // Twenty minutes after the first drop, but only five after the second.
        assertEquals(listOf(StartDash), on(DashRetryDue, ProjectionMachine.NO_DASH_STOP_MS + 70_000L))
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `the bike's Wi-Fi coming back tries the dash even after a long absence, and the count starts over`() {
        runningWithDashGoneAt(0L)
        val effects = on(BikeNetworkAvailable, ProjectionMachine.NO_DASH_STOP_MS + 5_000L)
        assertTrue(StartDash in effects)
        assertTrue(effects.none { it is NotifyNoDash })
        assertEquals(Phase.RUNNING, state.phase)
        assertEquals(null, state.dashGoneAtMs)
    }

    @Test
    fun `a dash taken by another app is not a dash that is gone`() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        on(DashChanged(DashLink.TAKEN), 0L)
        assertEquals(listOf(StartDash), on(DashRetryDue, 2 * ProjectionMachine.NO_DASH_STOP_MS))
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `a retry due after the rider already switched off does nothing`() {
        runningWithDashGoneAt(0L)
        on(Stop)
        on(ProjectionEvent.StopDone)
        assertEquals(emptyList<ProjectionEffect>(), on(DashRetryDue, 2 * ProjectionMachine.NO_DASH_STOP_MS))
    }

    @Test
    fun `the waits between tries go on once a minute until the stop`() {
        runningWithDashGoneAt(0L)
        on(DashRetryDue, 1_000L)
        val wait = on(DashChanged(DashLink.DROPPED), 10 * 60_000L).filterIsInstance<RetryDashIn>().single().delayMs
        assertEquals(60_000L, wait)
    }

    // ── the rider's choice: 5, 10, 20 minutes or none (Ajustes › No painel) ─────────────────────────────────────────

    @Test
    fun `a shorter limit stops sooner, and says how long it waited`() {
        val five = 5 * 60_000L
        runningWithDashGoneAt(0L)
        assertEquals(listOf(StartDash), on(DashRetryDue, five - 1, limitMs = five))
        on(DashChanged(DashLink.DROPPED), five - 1) // the try failed, as the next retry finds it
        assertEquals(listOf(NotifyNoDash(five), StopAndroidAuto, StopDash, Release, FinishStop), on(DashRetryDue, five, limitMs = five))
        assertEquals(Phase.STOPPED, state.phase)
    }

    @Test
    fun `no limit never stops it, however long the dash is gone`() {
        runningWithDashGoneAt(0L)
        val tenHours = 10 * 60 * 60_000L
        assertEquals(listOf(StartDash), on(DashRetryDue, tenHours, limitMs = 0L))
        assertEquals(Phase.RUNNING, state.phase)
    }

    @Test
    fun `a limit changed between two retries counts from when the dash went`() {
        runningWithDashGoneAt(0L)
        assertEquals(listOf(StartDash), on(DashRetryDue, 8 * 60_000L, limitMs = 20 * 60_000L))
        on(DashChanged(DashLink.DROPPED), 8 * 60_000L + 1)
        // The rider chose 10 minutes meanwhile: the dash has been gone for 11.
        assertTrue(on(DashRetryDue, 11 * 60_000L, limitMs = 10 * 60_000L).any { it is NotifyNoDash })
    }

    @Test
    fun `a dash gone while Android Auto is down too still stops the projection after twenty minutes (26 10 2026 review)`() {
        runningWithDashGoneAt(0L)
        on(AndroidAutoChanged(AaLinkState.SERVER_OFF), 1_000L)
        val waiting = on(DashRetryDue, 2_000L)
        assertEquals("no dash tried without Android Auto, but a retry is still planned", 1, waiting.count { it is RetryDashIn })
        assertFalse(StartDash in waiting)
        assertEquals(DashLink.OFF, state.dash)
        // still down, a minute later and so on
        assertTrue(on(DashRetryDue, 600_000L).any { it is RetryDashIn })
        val effects = on(DashRetryDue, ProjectionMachine.NO_DASH_STOP_MS)
        assertEquals(listOf(NotifyNoDash(ProjectionMachine.NO_DASH_STOP_MS), StopAndroidAuto, StopDash, Release, FinishStop), effects)
        assertEquals(Phase.STOPPED, state.phase)
    }

    @Test
    fun `Android Auto coming back while the dash waits for it tries the dash, and the count of its absence goes on`() {
        runningWithDashGoneAt(0L)
        on(AndroidAutoChanged(AaLinkState.SERVER_OFF), 1_000L)
        on(DashRetryDue, 2_000L)
        assertEquals(listOf(StartDash), on(AndroidAutoChanged(AaLinkState.ACTIVE), 3_000L))
        assertEquals(0L, state.dashGoneAtMs)
    }
}
