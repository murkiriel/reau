package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the dash session shares between threads (audit of 20/10/2026): the nav figures are one value, and connect() is
 * claimed in one step.
 */
class DashSessionSharedStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gates = CopyOnWriteArrayList<CountDownLatch>()

    private fun heldSession(): DashSession = DashSession(scope, socketFactory = {
        val gate = CountDownLatch(1).also { gates += it }
        gate.await()
        throw java.net.BindException("socket factory held closed by the test")
    })

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        scope.cancel()
    }

    /** A reader of the figures never sees the distance of one update with the unit of another. */
    @Test
    fun `the nav figures are read whole while another thread replaces them`() {
        val session = DashSession(scope)
        val stop = AtomicBoolean(false)
        val writer = Thread {
            var far = false
            while (!stop.get()) {
                far = !far
                if (far) {
                    session.updateNavInfo(0x14, 10, DashCommands.NAV_UNIT_KM_TENTHS, 32, DashCommands.NAV_UNIT_KM_TENTHS, road = "Rua Longe")
                } else {
                    session.updateNavInfo(0x14, 999, DashCommands.NAV_UNIT_METERS, 5, DashCommands.NAV_UNIT_METERS, road = "Rua Perto")
                }
            }
        }
        writer.start()
        val torn = mutableListOf<DashSession.NavFigures>()
        val until = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < until) {
            val n = session.navNow ?: continue
            val whole = (n.primaryDist == 10 && n.primaryUnit == DashCommands.NAV_UNIT_KM_TENTHS && n.road == "Rua Longe") ||
                (n.primaryDist == 999 && n.primaryUnit == DashCommands.NAV_UNIT_METERS && n.road == "Rua Perto")
            if (!whole) torn += n
        }
        stop.set(true)
        writer.join()
        assertTrue("figures from two updates in one: ${torn.firstOrNull()}", torn.isEmpty())
    }

    @Test
    fun `no guidance is no figures, and the ring is straight on`() {
        val session = DashSession(scope)
        assertNull(session.navNow)
        session.updateNavInfo(0x14, 250, DashCommands.NAV_UNIT_METERS, 32, DashCommands.NAV_UNIT_KM_TENTHS)
        assertNotNull(session.navNow)
        session.clearNavInfo()
        assertNull(session.navNow)
        assertEquals(DashCommands.NAV_MANEUVER_CONTINUE, session.ringNow)
    }

    /** Before: check _state, then set it; two callers at once both passed and each started a session on the same ports. */
    @Test
    fun `callers racing to connect start one session`() {
        repeat(60) { round ->
            val before = gates.size
            val session = heldSession()
            val callers = 8
            val barrier = CyclicBarrier(callers)
            val threads = List(callers) { Thread { barrier.await(); session.connect("RE_TEST_$round") } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val deadline = System.currentTimeMillis() + 2_000
            while (gates.size == before && System.currentTimeMillis() < deadline) Thread.sleep(2)
            Thread.sleep(15)
            assertEquals("round $round: one session started", 1, gates.size - before)
            session.disconnect()
        }
    }
}
