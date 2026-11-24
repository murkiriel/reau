package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Whether another app holds the dash's port:
 * another app on the dash's port may bind UDP 2002 with address reuse on, so a second app could bind it too and the
 * dash's packets would go to one or the other. A bind WITHOUT reuse fails while anyone holds the port: that is the
 * check.
 */
class DashPortsTest {

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun `a port nobody holds is free, and the check leaves it free`() {
        val port = freePort()
        assertTrue(DashPorts.isFree(port))
        assertTrue("checking twice: the check let go of it", DashPorts.isFree(port))
    }

    @Test
    fun `a port held with address reuse on, as another app may hold it, is taken`() {
        val port = freePort()
        DatagramSocket(null).use { other ->
            other.reuseAddress = true
            other.bind(InetSocketAddress(port))
            assertFalse(DashPorts.isFree(port))
        }
        assertTrue("free again once the other app lets go", DashPorts.isFree(port))
    }

    @Test
    fun `a port held without address reuse is taken too`() {
        val port = freePort()
        DatagramSocket(null).use { other ->
            other.bind(InetSocketAddress(port))
            assertFalse(DashPorts.isFree(port))
        }
    }

    @Test
    fun `the port checked for the dash is the one it answers on`() {
        assertEquals(2002, DashPorts.DASH_RX_PORT)
        assertEquals(DashSocket.RX_PORT, DashPorts.DASH_RX_PORT)
    }

    @Test
    fun `a bind that fails says the exception and its message for the log, and one that works says nothing`() {
        val port = freePort()
        assertNull(DashPorts.bindFailure(port))
        DatagramSocket(null).use { other ->
            other.bind(InetSocketAddress(port))
            val why = DashPorts.bindFailure(port)
            assertNotNull(why)
            assertTrue(why, why!!.startsWith("BindException: "))
            assertFalse("the failure is the system's own words", why.contains("\n"))
        }
    }

    @Test
    fun `the receive port decides and the log hears nothing when it is free, or says both ports when it is not`() {
        val said = mutableListOf<String>()
        val free = DashPorts.receivePortFree { said += it }
        if (free) assertTrue("nothing told for a port that binds", said.isEmpty())
        else {
            assertEquals(1, said.size)
            assertTrue(said[0], said[0].startsWith("could not bind UDP 2002 ("))
            assertTrue(said[0], said[0].contains("UDP 2000"))
        }
    }
}
