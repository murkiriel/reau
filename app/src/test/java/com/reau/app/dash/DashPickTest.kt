package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit of 20/10/2026 (bug 5): with several bikes in sight the first name in the scan list was taken and kept. */
class DashPickTest {
    private val now = 1_000_000_000L // microseconds since boot

    private fun seen(ssid: String, level: Int, agoS: Long = 1, freq: Int = 2_437) =
        ScanSeen(ssid, level, freq, now - agoS * 1_000_000L)

    @Test
    fun `with one dash in sight that is the dash`() {
        val choice = DashPick.strongest(listOf(seen("home", -40), seen("RE_AB12_345678", -61)), "RE_", now)
        assertEquals("RE_AB12_345678", choice?.name)
    }

    @Test
    fun `the strongest of several is taken, not the first in the list`() {
        val scan = listOf(seen("RE_NEIGHBOUR_111111", -78), seen("RE_MINE_222222", -52), seen("RE_FAR_333333", -85))
        val choice = DashPick.strongest(scan, "RE_", now)
        assertEquals("RE_MINE_222222", choice?.name)
        assertEquals(listOf("RE_MINE_222222", "RE_NEIGHBOUR_111111", "RE_FAR_333333"), choice?.signals?.map { it.first })
    }

    @Test
    fun `a recent weaker dash beats a stronger one the scan list only remembers`() {
        // A bike that left two minutes ago still strong in the list; the one here now is weaker.
        val scan = listOf(seen("RE_GONE_111111", -45, agoS = 120), seen("RE_HERE_222222", -70, agoS = 2))
        assertEquals("RE_HERE_222222", DashPick.strongest(scan, "RE_", now)?.name)
    }

    @Test
    fun `when every sighting is old the strongest of them is still taken`() {
        val scan = listOf(seen("RE_A_111111", -80, agoS = 300), seen("RE_B_222222", -60, agoS = 300))
        assertEquals("RE_B_222222", DashPick.strongest(scan, "RE_", now)?.name)
    }

    @Test
    fun `a dash heard by several access points counts once, by its best signal`() {
        val scan = listOf(seen("RE_ONE_111111", -80), seen("RE_ONE_111111", -50, freq = 5_180), seen("RE_TWO_222222", -60))
        val choice = DashPick.strongest(scan, "RE_", now)
        assertEquals("RE_ONE_111111", choice?.name)
        assertEquals(2, choice?.signals?.size)
        assertEquals(-50, choice?.signals?.first()?.second)
    }

    @Test
    fun `a tie keeps the order of the scan`() {
        val scan = listOf(seen("RE_FIRST_111111", -60), seen("RE_SECOND_222222", -60))
        assertEquals("RE_FIRST_111111", DashPick.strongest(scan, "RE_", now)?.name)
    }

    @Test
    fun `no network that begins with the prefix is no dash`() {
        assertNull(DashPick.strongest(listOf(seen("home", -40), seen("XRE_DASH", -50)), "RE_", now))
        assertNull(DashPick.strongest(emptyList(), "RE_", now))
    }

    @Test
    fun `the log line masks the names and tells why one was taken when there were several`() {
        val one = DashPick.strongest(listOf(seen("RE_AB12_345678", -61)), "RE_", now)!!.describe()
        assertTrue(one, "345678" !in one)
        val several = DashPick.strongest(listOf(seen("RE_AB12_345678", -61), seen("RE_CD34_901234", -75)), "RE_", now)!!.describe()
        assertTrue(several, "strongest and most recent of 2 dashes" in several)
        assertTrue(several, "345678" !in several && "901234" !in several)
        assertTrue(several, "-61 dBm" in several && "-75 dBm" in several)
    }
}
