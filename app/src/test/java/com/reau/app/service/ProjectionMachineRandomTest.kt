package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.fuzz.Seeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Many random sequences of the events the sides and the rider can send, from a fixed seed ([Seeded]); what the machine promises holds
 * after every one of them. Two of the things once thought provable only on the bike ("ACTIVE never after stopped", "Ligar and
 * Desligar almost together") are proved here for every order the events can come in.
 */
class ProjectionMachineRandomTest {
    private val events: List<ProjectionEvent> = buildList {
        add(ProjectionEvent.Start)
        add(ProjectionEvent.StartCard)
        add(ProjectionEvent.Prepared)
        add(ProjectionEvent.PrepareFailed("boom"))
        add(ProjectionEvent.Stop)
        add(ProjectionEvent.StopDone)
        AaLinkState.entries.forEach { add(ProjectionEvent.AndroidAutoChanged(it)) }
        DashLink.entries.forEach { add(ProjectionEvent.DashChanged(it)) }
        add(ProjectionEvent.DashRetryDue)
        add(ProjectionEvent.BikeNetworkAvailable)
        add(ProjectionEvent.DashSighted)
        add(ProjectionEvent.DashFirstJoin(true)); add(ProjectionEvent.DashFirstJoin(false))
        add(ProjectionEvent.NetworkServer(true)); add(ProjectionEvent.NetworkServer(false))
        add(ProjectionEvent.AndroidAutoVideo(true)); add(ProjectionEvent.AndroidAutoVideo(false))
        add(ProjectionEvent.AndroidAutoImageStale(true)); add(ProjectionEvent.AndroidAutoImageStale(false))
    }

    private val starts = setOf<ProjectionEvent>(ProjectionEvent.Start, ProjectionEvent.StartCard)
    private val startEffects = setOf<ProjectionEffect>(
        ProjectionEffect.StartAndroidAuto, ProjectionEffect.StartDash, ProjectionEffect.StartVideo, ProjectionEffect.Prepare,
    )

    private fun everySequence(salt: Long, check: (before: Projection, event: ProjectionEvent, step: Step, where: String) -> Unit) {
        val rnd = Seeded.random(salt)
        repeat(Seeded.ROUNDS / 3) { round ->
            var state = Projection()
            var now = 0L
            repeat(rnd.nextInt(60)) { index ->
                val event = events[rnd.nextInt(events.size)]
                now += rnd.nextInt(90_000)
                val step = ProjectionMachine.step(state, event, now)
                check(state, event, step, "round $round step $index $event from ${state.phase}/${state.androidAuto}/${state.dash}")
                state = step.state
            }
        }
    }

    @Test
    fun `a stopped projection starts nothing unless the rider asks, whatever else is heard`() = everySequence(11) { before, event, step, where ->
        if (before.phase == Phase.STOPPED && event !in starts) {
            assertEquals(where, Phase.STOPPED, step.state.phase)
            assertTrue("$where: ${step.effects}", step.effects.none { it in startEffects })
        }
    }

    @Test
    fun `Android Auto and the dash are started only by a running projection`() = everySequence(12) { _, _, step, where ->
        if (step.effects.any { it in setOf(ProjectionEffect.StartAndroidAuto, ProjectionEffect.StartDash, ProjectionEffect.StartVideo) }) {
            assertEquals(where, Phase.RUNNING, step.state.phase)
        }
    }

    @Test
    fun `a stopped projection holds no Android Auto session and no dash link`() = everySequence(13) { before, _, step, where ->
        // A stop that is being carried out ends in StopDone; a stopped, finished projection is the empty one (apart from a refused start's reason)
        if (step.state.phase == Phase.STOPPED && !step.state.stopping && !before.stopping) {
            assertEquals(where, AaLinkState.IDLE, step.state.androidAuto)
            assertEquals(where, DashLink.OFF, step.state.dash)
            assertFalse(where, step.state.streaming)
        }
    }

    @Test
    fun `the rider's stop always ends in a finished stop, whatever state it finds`() {
        val rnd = Seeded.random(14)
        repeat(Seeded.ROUNDS / 3) { round ->
            var state = Projection()
            repeat(rnd.nextInt(30)) { state = ProjectionMachine.step(state, events[rnd.nextInt(events.size)], 0L).state }
            val stopped = ProjectionMachine.step(state, ProjectionEvent.Stop, 0L)
            val done = ProjectionMachine.step(stopped.state, ProjectionEvent.StopDone, 0L)
            val where = "round $round from ${state.phase}"
            assertEquals(where, Phase.STOPPED, done.state.phase)
            assertFalse(where, done.state.stopping)
            // and a start is taken at once after it (Desligar then Ligar, almost together)
            assertEquals(where, Phase.PREPARING, ProjectionMachine.step(done.state, ProjectionEvent.Start, 0L).state.phase)
        }
    }

    @Test
    fun `the headline can be read from any state the events lead to`() = everySequence(15) { _, _, step, where ->
        assertNotNull(where, step.state.headline)
    }
}
