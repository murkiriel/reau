package com.reau.app.dash

import com.reau.app.dash.LocalAddressRetry.Proof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When REau tries the local address after Android refused to bind a socket to the dash's network (25/11/2026): once, only on a live Wi-Fi network with an IPv4 address. */
class LocalAddressRetryTest {
    @Test
    fun `a refused bind on a live network with an address is tried once through the local address`() {
        assertTrue(LocalAddressRetry.shouldTry(bindRefused = true, alive = true, hasIpv4 = true, alreadyTried = false))
    }

    @Test
    fun `a dead network is never tried, the 4_5 minute case stays as it is`() {
        assertFalse(LocalAddressRetry.shouldTry(bindRefused = true, alive = false, hasIpv4 = true, alreadyTried = false))
    }

    @Test
    fun `the same refusal is not tried a second time`() {
        assertFalse(LocalAddressRetry.shouldTry(bindRefused = true, alive = true, hasIpv4 = true, alreadyTried = true))
    }

    @Test
    fun `a network with no IPv4 address is not tried`() {
        assertFalse(LocalAddressRetry.shouldTry(bindRefused = true, alive = true, hasIpv4 = false, alreadyTried = false))
    }

    @Test
    fun `a bind that was not refused, or any other failure, is not tried`() {
        assertFalse(LocalAddressRetry.shouldTry(bindRefused = false, alive = true, hasIpv4 = true, alreadyTried = false))
        // The refusal is the bind's EPERM and nothing else: another socket error is not it.
        assertTrue(NetworkBlock.isBindRefusal(java.io.IOException("Binding socket to network 454 failed: EPERM")))
        assertFalse(NetworkBlock.isBindRefusal(java.net.BindException("Address already in use")))
        assertFalse(NetworkBlock.isBindRefusal(java.io.IOException("sendto failed: EPERM (Operation not permitted)")))
    }

    @Test
    fun `without the facts of the network the default never tries`() {
        assertFalse(LocalAddressRetry.NONE.alive)
        assertEquals(null, LocalAddressRetry.NONE.ipv4)
    }

    @Test
    fun `the route is proved by the dash answering our greeting within the time, and a dash that does not ends the try`() {
        assertEquals(Proof.WAIT, LocalAddressRetry.proof(answeredGreeting = false, sinceGreetingMs = 4_999))
        assertEquals(Proof.DID_NOT_ANSWER, LocalAddressRetry.proof(answeredGreeting = false, sinceGreetingMs = 5_000))
        assertEquals(Proof.WORKED, LocalAddressRetry.proof(answeredGreeting = true, sinceGreetingMs = 100))
        assertEquals(Proof.WORKED, LocalAddressRetry.proof(answeredGreeting = true, sinceGreetingMs = 4_999))
        assertEquals(5_000L, LocalAddressRetry.PROOF_MS)
    }

    @Test
    fun `the proof starts at the first greeting, or later when the dash is young and not ready for it yet`() {
        assertEquals("no counter seen", 500L, LocalAddressRetry.proofStartMs(burstStartMs = 500, first = null, firstAtMs = 0))
        assertEquals("a dash already ready", 500L, LocalAddressRetry.proofStartMs(500, first = 30, firstAtMs = 400))
        assertEquals("ready at 26: 4 s after the counter 22 was seen at 1 s", 5_000L, LocalAddressRetry.proofStartMs(500, first = 22, firstAtMs = 1_000))
        assertEquals("ready before the greeting", 500L, LocalAddressRetry.proofStartMs(500, first = 26, firstAtMs = 0))
        // and while it is not ready the time since the start is negative: the proof waits
        assertEquals(Proof.WAIT, LocalAddressRetry.proof(answeredGreeting = false, sinceGreetingMs = -3_000))
    }

    @Test
    fun `the lines say the local address and never a number`() {
        val lines = listOf(
            LocalAddressRetry.TRYING_LINE, LocalAddressRetry.WORKED_LINE, LocalAddressRetry.couldNotOpenLine("IOException"),
            LocalAddressRetry.silentLine(5_000), LocalAddressRetry.failedLine(15_000),
        )
        for (line in lines) {
            assertTrue(line, "local address" in line)
            assertFalse("an address in: $line", Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(line))
        }
        assertEquals("EPERM on the dash's network: trying once through its local address", LocalAddressRetry.TRYING_LINE)
    }

    @Test
    fun `the control and video sockets of the try are bound to the local address, the receive socket on every address, and every other caller's are as always`() {
        val mine = java.net.InetAddress.getByName("127.0.0.2")
        DashSocket(null, localAddress = mine).use { s ->
            val (control, receive, video) = s.testOnlyBoundTo()
            assertEquals("control on the local address", mine, control)
            assertEquals("video on the local address", mine, video)
            assertTrue("receive on every address: a unicast-bound socket would miss what the dash sends to the broadcast address", receive.isAnyLocalAddress)
        }
        DashSocket().use { s ->
            assertTrue("the normal sockets are bound as they always were: to no address in particular", s.testOnlyBoundTo().all { it.isAnyLocalAddress })
        }
    }
}
