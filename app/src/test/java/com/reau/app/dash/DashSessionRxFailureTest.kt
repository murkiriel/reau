package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit of 20/10/2026: an exception from handling one packet of the dash escaped the receive coroutine and crashed the app. A bad
 * packet is now dropped; only when every packet is bad does the session fail.
 */
class DashSessionRxFailureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var sock: DashSocket? = null
    private val errors = mutableListOf<String>()

    /** A sink that throws for the codes of the `0C` group (what [badPacket] carries) and takes anything else. */
    private val sink = object : DashReadingSink {
        override fun onValue(type: Int, sub: Int, value: ByteArray) {
            if (type == 0x0C) throw IllegalStateException("the storage is broken")
        }
        override fun flush() = Unit
    }

    @After
    fun tearDown() {
        sock?.close()
        session?.disconnect()
        scope.cancel()
    }

    private fun packet(type: Int, sub: Int, value: ByteArray): ByteArray {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
        val tlv = byteArrayOf(type.toByte(), sub.toByte(), ((value.size shr 8) and 0xFF).toByte(), (value.size and 0xFF).toByte())
        return header + tlv + value
    }

    private val badPacket get() = packet(0x0C, 0x14, byteArrayOf(0x01))
    private val goodPacket get() = packet(0x03, 0x05, byteArrayOf(0x01))

    private fun open(): DashSession {
        sock = DashSocket()
        return DashSession(scope, readings = sink).also { it.onError = { why -> errors += why }; session = it }
    }

    @Test
    fun `a packet whose handling throws is dropped, and the session goes on`() {
        val s = open()
        s.receivePacket(badPacket, sock!!)
        assertTrue("the session failed on one bad packet: $errors", errors.isEmpty())
        assertTrue(s.state.value != DashState.ERROR)
    }

    @Test
    fun `when every packet throws the session fails after a run of them, with a clear reason`() {
        val s = open()
        // Twenty in a row, as chosen from the ride logs (written out, not the constant: changing it is a decision made here too).
        repeat(19) { s.receivePacket(badPacket, sock!!) }
        assertTrue("failed too soon: $errors", errors.isEmpty())
        s.receivePacket(badPacket, sock!!)
        assertEquals(1, errors.size)
        assertTrue(errors.single(), "keep failing to be handled" in errors.single())
        assertEquals(DashState.ERROR, s.state.value)
    }

    @Test
    fun `a packet that is handled starts the count over`() {
        val s = open()
        repeat(DashSession.RX_EXCEPTIONS_BEFORE_FAIL - 1) { s.receivePacket(badPacket, sock!!) }
        s.receivePacket(goodPacket, sock!!)
        repeat(DashSession.RX_EXCEPTIONS_BEFORE_FAIL - 1) { s.receivePacket(badPacket, sock!!) }
        assertTrue("never 20 in a row, but failed: $errors", errors.isEmpty())
    }
}
