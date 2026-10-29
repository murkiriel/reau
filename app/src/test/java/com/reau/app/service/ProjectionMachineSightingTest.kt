package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionEffect.CancelDashRetry
import com.reau.app.service.ProjectionEffect.RetryDashIn
import com.reau.app.service.ProjectionEffect.StartDash
import com.reau.app.service.ProjectionEvent.AndroidAutoChanged
import com.reau.app.service.ProjectionEvent.DashChanged
import com.reau.app.service.ProjectionEvent.DashRetryDue
import com.reau.app.service.ProjectionEvent.DashSighted
import com.reau.app.service.ProjectionEvent.Prepared
import com.reau.app.service.ProjectionEvent.Start
import com.reau.app.service.ProjectionEvent.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride log of 20/10/2026: with the first approval pending the retry slept 2, 4, 8, 16 s; the dash was seen at 11:47:38 and asked
 * for at 11:47:45. Finding the dash in the scan cuts the wait.
 */
class ProjectionMachineSightingTest {
    private var state = Projection()

    private fun on(event: ProjectionEvent, nowMs: Long = 0L): List<ProjectionEffect> {
        val step = ProjectionMachine.step(state, event, nowMs)
        state = step.state
        return step.effects
    }

    private fun running() {
        on(Start)
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
    }

    @Test
    fun `a dash waiting out a 16 second retry is tried at once when it is seen`() {
        running()
        // 2, 4, 8 s waits gone by; the fourth, 16 s, is armed
        on(DashChanged(DashLink.DROPPED), 0L)
        on(DashRetryDue, 2_000L)
        on(DashChanged(DashLink.DROPPED), 2_100L)
        on(DashRetryDue, 6_100L)
        on(DashChanged(DashLink.DROPPED), 6_200L)
        on(DashRetryDue, 14_200L)
        val wait = on(DashChanged(DashLink.DROPPED), 14_300L).filterIsInstance<RetryDashIn>().single().delayMs
        assertEquals(16_000L, wait)
        assertEquals(listOf(CancelDashRetry, StartDash), on(DashSighted, 17_000L))
        assertEquals(DashLink.CONNECTING, state.dash)
    }

    @Test
    fun `the pace of the waits goes on, unlike the bike's Wi-Fi coming back`() {
        running()
        on(DashChanged(DashLink.DROPPED), 0L)
        on(DashRetryDue, 2_000L)
        on(DashChanged(DashLink.DROPPED), 2_100L)
        val retries = state.dashRetries
        val goneAt = state.dashGoneAtMs
        on(DashSighted, 3_000L)
        assertEquals(retries, state.dashRetries)
        assertEquals(goneAt, state.dashGoneAtMs)
    }

    @Test
    fun `with no retry waiting a sighting changes nothing`() {
        assertTrue(on(DashSighted).isEmpty())
        assertEquals(Projection(), state)
        on(Start)
        assertTrue(on(DashSighted).isEmpty())
        on(Prepared)
        on(AndroidAutoChanged(AaLinkState.ACTIVE))
        val connecting = state
        assertEquals(DashLink.CONNECTING, connecting.dash)
        assertTrue(on(DashSighted).isEmpty())
        on(DashChanged(DashLink.READY))
        val ready = state
        assertTrue(on(DashSighted).isEmpty())
        assertEquals(ready, state)
        on(DashChanged(DashLink.TAKEN))
        assertTrue("another app holds the port: seeing the dash does not change that", on(DashSighted).isEmpty())
        on(Stop)
        on(ProjectionEvent.StopDone)
        assertTrue(on(DashSighted).isEmpty())
    }
}
