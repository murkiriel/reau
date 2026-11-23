package com.reau.app.dash

import org.junit.Assert.assertEquals
import java.io.IOException
import java.net.SocketException
import org.junit.Test

/** An EPERM from the network says what it means in the ride log, the others stay as they were. */
class NetworkBlockTest {
    @Test
    fun `an EPERM is the network blocked by Android`() {
        assertEquals(
            "SocketException: Binding socket to network 275 failed: EPERM (Operation not permitted) — network blocked by Android",
            NetworkBlock.describe(SocketException("Binding socket to network 275 failed: EPERM (Operation not permitted)")),
        )
        assertEquals(
            "IOException: sendto failed: EPERM (Operation not permitted) — network blocked by Android",
            NetworkBlock.describe(IOException("sendto failed: EPERM (Operation not permitted)")),
        )
    }

    @Test
    fun `other errors are told as before`() {
        assertEquals(
            "IOException: sendto failed: ENETUNREACH (Network is unreachable)",
            NetworkBlock.describe(IOException("sendto failed: ENETUNREACH (Network is unreachable)")),
        )
        assertEquals("IOException: null", NetworkBlock.describe(IOException()))
    }
}

/** The EPERM of binding a socket to the dash's network is told from any other error: it is the one that drops the network (31/10/2026). */
class NetworkBlockBindRefusalTest {
    @Test
    fun `the line of the log of 31 10 is a bind refusal`() {
        org.junit.Assert.assertTrue(
            NetworkBlock.isBindRefusal(SocketException("Binding socket to network 454 failed: EPERM (Operation not permitted)")),
        )
    }

    @Test
    fun `an EPERM of a send, a refusal of another kind and an error with no message are not`() {
        org.junit.Assert.assertFalse(NetworkBlock.isBindRefusal(IOException("sendto failed: EPERM (Operation not permitted)")))
        org.junit.Assert.assertFalse(NetworkBlock.isBindRefusal(SocketException("Binding socket to network 454 failed: ENETUNREACH")))
        org.junit.Assert.assertFalse(NetworkBlock.isBindRefusal(IOException()))
    }
}

/** The ride log says which network is the app's default and whether a VPN covers REau (plan 32, C12): only a line, no decision. */
class NetworkBlockVpnLineTest {
    @Test
    fun `the default network is told with the VPN answer`() {
        assertEquals("default network: cell (VPN: no)", NetworkBlock.defaultLine("cell", false))
        assertEquals("default network: vpn (VPN: yes)", NetworkBlock.defaultLine("vpn", true))
        assertEquals("default network: wifi (VPN: unknown)", NetworkBlock.defaultLine("wifi", null))
        assertEquals("default network: none (VPN: no)", NetworkBlock.defaultLine(null, false))
    }

    @Test
    fun `a refused bind gets the VPN note only when a VPN covers REau`() {
        assertEquals("a VPN covers REau: binding to the dash network may be refused", NetworkBlock.vpnRefusalNote(true))
        org.junit.Assert.assertNull(NetworkBlock.vpnRefusalNote(false))
    }
}
