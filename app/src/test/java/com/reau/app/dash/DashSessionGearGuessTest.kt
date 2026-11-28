package com.reau.app.dash

import com.reau.app.dash.protocol.TelemetryEvent
import com.reau.app.data.RideDiagnostics
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The dash's `09 08` reaches the ride log's line ([GearGuessWatch]) and still goes on to the telemetry as before: the line changes nothing REau
 * does, and a failure making it never ends the receive loop.
 */
class DashSessionGearGuessTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sock: DashSocket? = null
    private var session: DashSession? = null

    @After
    fun tearDown() {
        sock?.close()
        session?.disconnect()
        scope.cancel()
        RideDiagnostics.testOnlyReset()
    }

    private fun rawPacket(type: Int, sub: Int, value: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        return header + byteArrayOf(type.toByte(), sub.toByte(), ((value.size shr 8) and 0xFF).toByte(), (value.size and 0xFF).toByte()) + value
    }

    private fun feed(speedProvider: () -> Double?, vararg packets: ByteArray): List<TelemetryEvent> = runBlocking {
        val s = DashSession(scope, speedProvider = speedProvider).also { session = it }
        val events = mutableListOf<TelemetryEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.telemetry.collect { events.add(it) } }
        val sockLocal = DashSocket().also { sock = it }
        packets.forEach { s.dispatchIncoming(it, sockLocal) }
        yield()
        job.cancel()
        events
    }

    @Test
    fun `a 09 08 asks for the speed, and goes on to the telemetry as it did`() {
        var asked = 0
        val events = feed({ asked++; 50.0 }, rawPacket(0x09, 0x08, byteArrayOf(0xAA.toByte())))
        assertEquals(1, asked)
        assertEquals(1, events.size)
        assertEquals(0x09, events[0].type)
        assertEquals(0x08, events[0].sub)
    }

    @Test
    fun `a failure of the speed never stops the telemetry or the packets after`() {
        val events = feed(
            { error("no speed") },
            rawPacket(0x09, 0x08, byteArrayOf(0x55)), rawPacket(0x0C, 0x02, byteArrayOf(0x11)),
        )
        assertEquals(listOf(0x09, 0x0C), events.map { it.type })
    }

    @Test
    fun `other codes of the 09 group, and a 09 08 that is not one byte, do not make the line`() {
        var asked = 0
        feed(
            { asked++; null },
            rawPacket(0x09, 0x07, byteArrayOf(0xAA.toByte())), rawPacket(0x09, 0x09, byteArrayOf(0xAA.toByte())),
            rawPacket(0x09, 0x08, byteArrayOf(0x55, 0x55)), rawPacket(0x0C, 0x08, byteArrayOf(0x55)),
        )
        assertEquals(0, asked)
    }

    @Test
    fun `each connection starts the line again`() {
        // 26/11/2026: it was a source test of beginAuthState; now the session says it. The same reading twice is one line, and after the start of a new connection it is told again.
        val file = tmp.newFile("ride-gear-guess.log")
        RideDiagnostics.testOnlySetFile(file)
        val s = DashSession(scope).also { session = it }
        val socket = DashSocket().also { sock = it }
        val reading = rawPacket(0x09, 0x08, byteArrayOf(0xAA.toByte()))
        fun told(): Int {
            RideDiagnostics.logAndWait("ui", "end")
            return file.readLines().count { "09 08: AA (in gear?) (first reading of the connection)" in it }
        }
        s.dispatchIncoming(reading, socket)
        s.dispatchIncoming(reading, socket)
        assertEquals("the same reading again says nothing", 1, told())
        s.beginAuthState(null)
        s.dispatchIncoming(reading, socket)
        assertEquals("a new connection tells its first reading again", 2, told())
    }

    @Test
    fun `the service gives the session the navigation's speed`() {
        val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
        assertTrue(live, "speedProvider = { navSpeed.kmhAt(android.os.SystemClock.elapsedRealtime()) }" in live)
    }
}
