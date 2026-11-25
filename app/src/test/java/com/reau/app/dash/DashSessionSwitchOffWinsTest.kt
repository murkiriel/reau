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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The switch-off wins. fail() passes its guards, closes the socket and flushes the readings, and only then writes ERROR; a disconnect() that runs
 * in that gap writes IDLE, and the ERROR that followed left a rider who had turned the projection off with an error on the screen.
 *
 * No timing here: the readings' sink is called by fail() between its last guard and the write of ERROR, which is exactly the gap, and the sink of
 * this test runs the other thread's work right there and waits for it to be done with.
 */
class DashSessionSwitchOffWinsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reported = CopyOnWriteArrayList<String>()
    private val gate = CountDownLatch(1)
    private val logFile = java.nio.file.Files.createTempFile("switch-off", ".log").toFile()
    private var session: DashSession? = null

    @After
    fun tearDown() {
        session?.disconnect()
        gate.countDown()
        scope.cancel()
        com.reau.app.data.RideDiagnostics.testOnlyReset()
        logFile.delete()
    }

    /** A connecting session whose teardown by fail(), the first time, is met halfway by [other] from another thread. Its connect() waits at [gate]. */
    private fun connectingMetHalfway(other: (DashSession) -> Unit): DashSession {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val flushes = AtomicInteger()
        lateinit var s: DashSession
        val sink = object : DashReadingSink {
            override fun onValue(type: Int, sub: Int, value: ByteArray) = Unit
            override fun flush() {
                if (flushes.incrementAndGet() != 1) return
                val t = thread(name = "switch-off") { other(s) }
                t.join(JOIN_MS)
                assertFalse("the other thread never returned", t.isAlive)
            }
        }
        s = DashSession(scope, socketFactory = { gate.await(); throw java.net.BindException("held closed by the test") }, readings = sink)
        s.onError = { why -> reported += why }
        session = s
        s.connect("RE_TEST_1234")
        assertEquals(DashState.CONNECTING, s.state.value)
        return s
    }

    @Test
    fun `a failure already tearing the session down does not write ERROR after the switch-off`() {
        val s = connectingMetHalfway { it.disconnect() }

        s.fail("Dash stopped responding — connection lost")

        assertEquals(DashState.IDLE, s.state.value)
        assertEquals("the rider turned it off: no notice", emptyList<String>(), reported.toList())
    }

    @Test
    fun `the failure that lost to the switch-off goes to the ride log`() {
        val s = connectingMetHalfway { it.disconnect() }

        s.fail("Dash stopped responding — connection lost")

        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        val lines = logFile.readLines()
        assertTrue(
            "the log said nothing of the lost failure: $lines",
            lines.any { "a failure after the switch-off, not told to the rider: Dash stopped responding — connection lost" in it },
        )
    }

    @Test
    fun `a switch-off between the last look and the write of ERROR still wins`() {
        val s = connectingMetHalfway { }
        s.testOnlyBeforeErrorWrite = {
            s.testOnlyBeforeErrorWrite = null // once: the write looks again after the switch-off
            val t = thread(name = "switch-off") { s.disconnect() }
            t.join(JOIN_MS)
            assertFalse("the switch-off never returned", t.isAlive)
        }

        s.fail("Dash stopped responding — connection lost")

        assertEquals(DashState.IDLE, s.state.value)
        assertEquals("the rider turned it off: no notice", emptyList<String>(), reported.toList())
    }

    @Test
    fun `a failure that finished before the switch-off is told, and the switch-off still ends in IDLE`() {
        val s = connectingMetHalfway { }

        s.fail("Dash stopped responding — connection lost")
        assertEquals(DashState.ERROR, s.state.value)
        assertEquals(listOf("Dash stopped responding — connection lost"), reported.toList())

        s.disconnect()

        assertEquals(DashState.IDLE, s.state.value)
        assertEquals("one notice only", listOf("Dash stopped responding — connection lost"), reported.toList())
    }

    private companion object {
        const val JOIN_MS = 10_000L
    }
}
