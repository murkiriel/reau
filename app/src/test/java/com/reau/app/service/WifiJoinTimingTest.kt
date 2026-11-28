package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How long each step of the phone's own Wi-Fi join took, from the supplicant's states (the reconnection after a silence), told once when the join ends. */
class WifiJoinTimingTest {
    @Test
    fun `a join that completes tells each step and the total`() {
        val j = WifiEvents.JoinTiming()
        assertNull(j.state("ASSOCIATING", 0, 1_000))
        assertNull(j.state("ASSOCIATED", 0, 1_040))
        assertNull(j.state("FOUR_WAY_HANDSHAKE", 0, 1_050))
        assertEquals(
            "wifi join ended in COMPLETED after 460 ms: ASSOCIATING, ASSOCIATED +40 ms, FOUR_WAY_HANDSHAKE +10 ms, COMPLETED +410 ms",
            j.state("COMPLETED", 0, 1_460),
        )
    }

    @Test
    fun `a join that ends in a disconnection tells the error the broadcast carried`() {
        val j = WifiEvents.JoinTiming()
        j.state("ASSOCIATING", 0, 100)
        j.state("FOUR_WAY_HANDSHAKE", 0, 130)
        assertEquals(
            "wifi join ended in DISCONNECTED after 2000 ms: ASSOCIATING, FOUR_WAY_HANDSHAKE +30 ms, DISCONNECTED +1970 ms, error: authentication failed",
            j.state("DISCONNECTED", 1, 2_100),
        )
        j.state("ASSOCIATING", 0, 3_000)
        assertEquals("wifi join ended in INACTIVE after 100 ms: ASSOCIATING, INACTIVE +100 ms, error 7", j.state("INACTIVE", 7, 3_100))
    }

    @Test
    fun `an error that came with a step of the join is not lost at its end`() {
        val j = WifiEvents.JoinTiming()
        j.state("FOUR_WAY_HANDSHAKE", 1, 10)
        assertEquals("wifi join ended in DISCONNECTED after 5 ms: FOUR_WAY_HANDSHAKE, DISCONNECTED +5 ms, error: authentication failed", j.state("DISCONNECTED", 0, 15))
    }

    @Test
    fun `a state with no join under way says nothing, and a repeated step is not counted twice`() {
        val j = WifiEvents.JoinTiming()
        assertNull(j.state("DISCONNECTED", 0, 10))
        assertNull(j.state("SCANNING", 0, 20))
        j.state("ASSOCIATING", 0, 100)
        j.state("ASSOCIATING", 0, 150)
        assertEquals("wifi join ended in COMPLETED after 200 ms: ASSOCIATING, COMPLETED +200 ms", j.state("COMPLETED", 0, 300))
        assertNull("the join is over: the next disconnection is not one", j.state("DISCONNECTED", 0, 400))
    }

    @Test
    fun `a scan in the middle of a join ends it`() {
        val j = WifiEvents.JoinTiming()
        j.state("ASSOCIATING", 0, 0)
        assertEquals("wifi join ended in SCANNING after 50 ms: ASSOCIATING, SCANNING +50 ms", j.state("SCANNING", 0, 50))
    }
}
