package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerResult
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Not only quick, it has to look quick: the cover of the walk
 * goes up at the rider's Ligar or Desligar when the walk is sure to come, the walk takes it over, it goes when no walk
 * comes, and Cancelar on it before the walk means no walk. The menu is a pretend driver here: nothing is walked.
 */
@RunWith(AndroidJUnit4::class)
class AaServerStarterTapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = SettingsStore.open(context)
    private val saved = store.load()
    private val before = AaServerStarter.driver
    private val did = CopyOnWriteArrayList<String>()

    // Held walks end only when the test says so ([finishHeld]); meanwhile, as the real service, another is refused.
    @Volatile private var holdWalks = false
    @Volatile private var held: Pair<ServerMenuRun, (ServerMenuRun.Outcome) -> Unit>? = null

    private val driver = object : AaServerStarter.MenuDriver {
        override fun drive(run: ServerMenuRun, done: (ServerMenuRun.Outcome) -> Unit): Boolean {
            if (held != null) {
                did += "refused ${run.mode}"
                return false
            }
            did += "drive ${run.mode}"
            if (holdWalks) {
                held = run to done
                return true
            }
            // As the real one: the outcome comes later, on the main thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(outcomeOf(run)) }
            return true
        }

        override fun holdCover(mode: ServerMenuRun.Mode, maxMs: Long) {
            did += "hold $mode"
        }

        override fun dropCover() {
            did += "drop"
        }
    }

    private fun outcomeOf(run: ServerMenuRun) =
        if (run.mode == ServerMenuRun.Mode.STOP) ServerMenuRun.Outcome.TAPPED_STOP else ServerMenuRun.Outcome.ALREADY_RUNNING

    private fun finishHeld() {
        val (run, done) = checkNotNull(held)
        held = null
        instrumentation.runOnMainSync { done(outcomeOf(run)) }
        settle()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    @Before
    fun setUp() {
        holdWalks = false
        held = null
        // A walk (and so its cover) is never tried with the phone locked or the screen off.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Thread.sleep(SETTLE_MS)
        store.save(saved.copy(aaServerAuto = true))
        AaServerStarter.stopButton = null
        AaServerStarter.driver = driver
        // The emulator's Android Auto is a stub without its settings screen, which the walk opens.
        AaServerStarter.settingsOpen = { true }
    }

    @After
    fun tearDown() {
        held?.let { (run, done) -> held = null; instrumentation.runOnMainSync { done(outcomeOf(run)) } }
        AaServerStarter.driver = before
        AaServerStarter.settingsOpen = com.reau.app.aa.AndroidAuto::settingsOpen
        AaServerStarter.dialSoon = null
        store.save(saved)
    }

    private fun settle() = instrumentation.waitForIdleSync()

    /** The projection's end has been told: the walk that switches the server off waits [com.reau.app.aa.StopWalkGate.SETTLE_MS] after it. */
    private fun settleStop() {
        settle()
        Thread.sleep(com.reau.app.aa.StopWalkGate.SETTLE_MS + SETTLE_MS)
        settle()
    }

    /** A projection that has seen the server up, as after Android Auto connected. */
    private fun projectionWithTheServerUp(): AaServerHooks = AaServerStarter.hooks(context).apply {
        started {}
        linkChanged(AaLinkState.ACTIVE)
        settle()
        did.clear()
    }

    @Test
    fun desligarWithTheServerSeenUpPutsTheCoverUpAtOnceAndTheWalkTakesItOver() {
        val projection = projectionWithTheServerUp()
        AaServerStarter.riderSwitched(context, on = false)
        settle()
        assertEquals("at the tap, before the session's end", listOf("hold STOP"), did.toList())
        projection.stopped()
        settleStop()
        assertEquals(listOf("hold STOP", "drive STOP"), did.toList())
        assertEquals(ServerResult.STOPPED, AaServerStarter.status.value)
    }

    @Test
    fun desligarWithTheServerNeverSeenUpPutsNoCover() {
        val projection = AaServerStarter.hooks(context).apply { started {} }
        AaServerStarter.riderSwitched(context, on = false)
        settle()
        projection.stopped()
        settleStop()
        assertEquals("no cover, no walk; only the drop of a cover that is not there", listOf("drop"), did.toList())
    }

    @Test
    fun cancelledAtTheTapTheWalkIsNotMade() {
        val projection = projectionWithTheServerUp()
        AaServerStarter.riderSwitched(context, on = false)
        settle()
        instrumentation.runOnMainSync { AaServerStarter.cancelledBeforeTheWalk() }
        projection.stopped()
        settleStop()
        assertEquals(listOf("hold STOP"), did.toList())
        assertEquals(ServerResult.LEFT_ON, AaServerStarter.status.value)
    }

    @Test
    fun ligarAfterREauSwitchedTheServerOffPutsTheCoverUpAtOnceAndAnAnswerTakesItDown() {
        projectionWithTheServerUp().stopped()
        settleStop()
        assertEquals(ServerResult.STOPPED, AaServerStarter.status.value)
        did.clear()

        AaServerStarter.riderSwitched(context, on = true)
        settle()
        assertEquals("at the tap, before the first dial", listOf("hold START"), did.toList())
        // The server was up after all (switched on by hand): Android Auto answers, no walk, the cover goes.
        AaServerStarter.hooks(context).apply { started {} }.linkChanged(AaLinkState.ACTIVE)
        settle()
        assertEquals(listOf("hold START", "drop"), did.toList())
    }

    /**
     * Observed on a device: Desligar and, a second later, Ligar. The old projection's walk that
     * switches the server off came after the new Ligar, and switched it off under the new projection. A Ligar after
     * the Desligar keeps the server on: the new projection needs it.
     */
    @Test
    fun aLigarRightAfterADesligarKeepsTheServerOn() {
        val old = projectionWithTheServerUp()
        AaServerStarter.riderSwitched(context, on = false)
        AaServerStarter.riderSwitched(context, on = true)
        old.stopped()
        settleStop()
        assertEquals("no walk switching it off, and the Desligar's cover goes", listOf("hold STOP", "drop"), did.toList())
    }

    private fun portUpNow(): Boolean {
        var up = false
        instrumentation.runOnMainSync { up = AaServerStarter.portUp() }
        return up
    }

    /** The 20:01 walk of 20/10/2026: the server answered before the walk began, the menu said "stop", and REau restarted a good server. */
    @Test
    fun aStartWalkThatBeginsWithTheServerAlreadyAnsweringHearsItInTheWait() {
        projectionWithTheServerUp()
        holdWalks = true
        AaServerStarter.retry(context)
        settle()
        assertEquals(listOf("drive START"), did.toList())
        assertTrue("the link said the server answers: the wait has it", portUpNow())
    }

    @Test
    fun aStartWalkThatBeginsWithTheServerClosedHearsNothingYet() {
        val projection = AaServerStarter.hooks(context).apply { started {} }
        projection.linkChanged(AaLinkState.SERVER_OFF)
        settle()
        did.clear()
        holdWalks = true
        AaServerStarter.retry(context)
        settle()
        assertEquals(listOf("drive START"), did.toList())
        assertEquals("the port is closed: the wait waits for it", false, portUpNow())
    }

    @Test
    fun aDesligarAloneStillSwitchesTheServerOff() {
        val projection = projectionWithTheServerUp()
        AaServerStarter.riderSwitched(context, on = true) // a Ligar while it ran: nothing to start
        AaServerStarter.riderSwitched(context, on = false)
        projection.stopped()
        settleStop()
        assertEquals(listOf("hold STOP", "drive STOP"), did.toList())
    }

    /**
     * Observed on a device: the new projection asked to switch the server on while the old one's walk
     * switching it off still went on; it was refused ("a walk is already going on") and never made again. Now it is
     * made right after the walk going on.
     */
    @Test
    fun aWalkAskedForWhileAnotherGoesOnIsMadeRightAfterIt() {
        holdWalks = true
        projectionWithTheServerUp().stopped()
        settleStop()
        val next = AaServerStarter.hooks(context).apply { started {} }
        next.linkChanged(AaLinkState.SERVER_OFF)
        settle()
        assertEquals(listOf("drive STOP", "refused START"), did.toList())
        holdWalks = false
        finishHeld()
        assertEquals(listOf("drive STOP", "refused START", "drive START"), did.toList())
    }

    @Test
    fun withoutTheAutomaticSwitchThereIsNoCoverAtTheTap() {
        store.save(saved.copy(aaServerAuto = false))
        projectionWithTheServerUp()
        AaServerStarter.riderSwitched(context, on = false)
        settle()
        assertEquals(emptyList<String>(), did.toList())
    }

    private companion object {
        const val SETTLE_MS = 500L
    }
}
