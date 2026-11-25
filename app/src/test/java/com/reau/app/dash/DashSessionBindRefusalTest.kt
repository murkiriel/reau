package com.reau.app.dash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ride log of 31/10/2026: the session's sockets were refused on network 454 with EPERM, ten times in 4.5 minutes, and the network that
 * refused them was handed back each time. The session now tells whoever holds the network, before it reports the failure.
 */
class DashSessionBindRefusalTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private val events = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
    }

    private fun await(what: String, until: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!until()) {
            assertTrue("timed out waiting for $what: $events", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    private fun open(factory: (android.net.Network?) -> DashSocket): DashSession =
        DashSession(scope, socketFactory = factory).also {
            it.onBindResult = { _, refused -> events += "bind refused=$refused" }
            it.onError = { why -> events += "error: $why" }
            session = it
        }

    @Test
    fun `an EPERM on the network is told to the holder of the network before the failure is reported`() {
        val s = open { throw SocketException("Binding socket to network 454 failed: EPERM (Operation not permitted)") }
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals("bind refused=true", events[0])
        assertTrue(events[1], events[1].startsWith("error: SocketException: Binding socket to network 454 failed: EPERM"))
        assertEquals(DashState.ERROR, s.state.value)
    }

    @Test
    fun `another socket error is a failure and no word about the network`() {
        val s = open { throw SocketException("Address already in use") }
        s.connect("RE_TEST_1234")
        await("the failure") { events.any { it.startsWith("error") } }
        assertEquals(1, events.size)
    }

    @Test
    fun `sockets that opened are told too, so a run of refusals starts over`() {
        val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 1)
        val s = open { DashSocket(it, ctrlDest = loopback, rtpDest = loopback) }
        s.connect("RE_TEST_1234")
        await("the sockets") { events.contains("bind refused=false") }
        assertEquals("bind refused=false", events[0])
    }
}
