package com.reau.app.ui

import android.util.Log
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.aa.AaLinkState
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The main screen's clock (the time on Desligar) must recompose only the text that shows it. The second's tick is a state read inside a
 * function that only that text calls ([rememberElapsed]); a change that read it higher up would recompose the bridge card, the warnings
 * and the shortcuts every second, and no JVM test sees that, because it is the compiler's and the runtime's work.
 *
 * The count comes from the runtime's own composition observer (the one the tooling uses): it is told each recompose scope that is
 * entered while the screen recomposes. The test lets the clock run for a few seconds of the test clock (the effect's `delay` is on it too)
 * and requires that (1) the very same few scopes are entered in every second, never more than [MAX_PER_TICK], and (2) that is a small
 * part of what a real change of state recomposes, which is counted on the same screen as the yardstick.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, InternalComposeApi::class)
@RunWith(AndroidJUnit4::class)
class MainScreenRecompositionTest {
    @get:Rule val compose = createComposeRule()

    /** Every recompose scope entered since the last [reset], by identity, and how many times in all. */
    private class Entries {
        val scopes: MutableSet<RecomposeScope> = Collections.newSetFromMap(IdentityHashMap())
        var count = 0
        fun reset() {
            scopes.clear()
            count = 0
        }
    }

    private val entries = Entries()
    private var observing: CompositionObserverHandle? = null
    private var state by mutableStateOf(MainUiState())
    private var startMs = 0L

    private val counter = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) = Unit
        override fun onScopeEnter(scope: RecomposeScope) {
            entries.scopes += scope
            entries.count++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
        override fun onScopeExit(scope: RecomposeScope) = Unit
        override fun onEndComposition(composition: ObservableComposition) = Unit
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }

    private fun showRunning(dash: DashLink) {
        state = MainUiState(
            Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.ACTIVE, dash = dash),
            androidAutoVersion = "17.4.661804", startedAtMs = startMs,
        )
    }

    @Test
    fun theClockRecomposesOnlyItsOwnText() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val screen = currentComposer.composition as ObservableComposition
            SideEffect { if (observing == null) observing = screen.setObserver(counter) }
            MainScreen(state, {}, {}, {}, clock = { compose.mainClock.currentTime })
        }
        compose.mainClock.advanceTimeByFrame()
        startMs = compose.mainClock.currentTime
        showRunning(DashLink.CONNECTING)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertTextEquals("00:00")

        // The yardstick: a real change of what the screen shows (the dash becomes ready) recomposes much of it.
        entries.reset()
        showRunning(DashLink.READY)
        compose.mainClock.advanceTimeBy(100)
        val change = entries.scopes.size
        Log.i("RecompTest", "yardstick change of state: scopes=$change entries=${entries.count}")

        // The clock running: five seconds, one tick at a time.
        val perTick = mutableListOf<Set<RecomposeScope>>()
        for (second in 1..SECONDS) {
            entries.reset()
            compose.mainClock.advanceTimeBy(TICK_MS)
            perTick += Collections.newSetFromMap<RecomposeScope>(IdentityHashMap()).also { it += entries.scopes }
            Log.i("RecompTest", "second $second: scopes=${entries.scopes.size} entries=${entries.count}")
        }
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertTextEquals("00:0$SECONDS")

        val first = perTick.first()
        assertTrue("the clock's second recomposes nothing: the text did not tick", first.isNotEmpty())
        for ((index, scopes) in perTick.withIndex()) {
            assertEquals("second ${index + 1} recomposed other scopes than the first second did", first.size, scopes.size)
            assertTrue("second ${index + 1} recomposed other scopes than the first second did", scopes.all { s -> first.any { it === s } })
        }
        assertTrue("the clock's second recomposes ${first.size} scopes (at most $MAX_PER_TICK: the time's own text)", first.size <= MAX_PER_TICK)
        assertTrue("the yardstick must be much bigger ($change scopes) than one second of the clock (${first.size})", change >= first.size * 3)
        observing?.dispose()
    }

    private companion object {
        const val SECONDS = 5
        const val TICK_MS = 1_000L
        const val MAX_PER_TICK = 4
    }
}
