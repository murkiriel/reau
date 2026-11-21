package com.reau.app.dash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ride log of 21/11/2026, 07:25 to 07:29: the dash's Wi-Fi hung for real, [StuckDashWatch] gave the verdict at 07:26:00, and the rider switched the
 * projection off and on three times instead of the ignition. Each switch-off took the notice away and the next start needed the count again.
 * [StuckMemory] keeps the verdict across the projection's switch-offs; only the ignition (the dash gone from the scan), the dash joined or five
 * minutes clear it.
 */
class StuckMemoryTest {
    private val dash = "RE_AB12_345678"
    private val good = DashStuck.Sighting.GOOD

    @Before @After
    fun clean() = StuckMemory.forget()

    private fun since(sighting: DashStuck.Sighting, nowMs: Long, ssid: String = dash, prefix: Boolean = false) =
        StuckMemory.sinceVerdictMs(ssid, prefix, sighting, nowMs)

    @Test
    fun `without a verdict nothing changes, whatever the scan says`() {
        for (s in DashStuck.Sighting.entries) assertNull(s.name, since(s, 10_000L))
    }

    @Test
    fun `a verdict is held and the first good try of a later start fires with the time since it`() {
        StuckMemory.remember(dash, false, 1_000L)
        assertEquals(47_000L, since(good, 48_000L))
    }

    @Test
    fun `the manual disconnect does not forget it, only the manager's join does`() {
        StuckMemory.remember(dash, false, 1_000L)
        // A disconnect never calls forget: the memory is still there at the next start (the three switch-offs of 07:27 to 07:28).
        assertNotNull(since(good, 22_000L))
        assertNotNull(since(good, 50_000L))
        StuckMemory.forget()
        assertNull("the dash joined: the manager forgets", since(good, 51_000L))
    }

    @Test
    fun `a try with the dash gone from the scan forgets it for good`() {
        StuckMemory.remember(dash, false, 1_000L)
        assertNull(since(DashStuck.Sighting.GONE, 20_000L))
        assertNull("and it stays forgotten when the dash is back in the scan", since(good, 30_000L))
    }

    @Test
    fun `a scan that says nothing neither fires nor forgets`() {
        StuckMemory.remember(dash, false, 1_000L)
        assertNull(since(DashStuck.Sighting.UNKNOWN, 20_000L))
        assertEquals(29_000L, since(good, 30_000L))
    }

    @Test
    fun `five minutes after the last verdict it is forgotten`() {
        StuckMemory.remember(dash, false, 1_000L)
        assertNotNull("at the limit it still holds", since(good, 1_000L + StuckMemory.REMEMBER_MS))
        assertNull(since(good, 1_001L + StuckMemory.REMEMBER_MS))
        assertNull("and does not come back", since(good, 2_000L))
    }

    @Test
    fun `a new verdict starts the five minutes again`() {
        StuckMemory.remember(dash, false, 1_000L)
        StuckMemory.remember(dash, false, 200_000L)
        assertNotNull(since(good, 450_000L))
    }

    @Test
    fun `another dash does not count, and does not make it forgotten`() {
        StuckMemory.remember(dash, false, 1_000L)
        assertNull(since(good, 20_000L, ssid = "RE_ZZ99_000000"))
        assertNotNull("the same dash still does", since(good, 21_000L))
    }

    @Test
    fun `a start by the prefix and one by the exact name are the same dash`() {
        StuckMemory.remember("RE_", true, 1_000L)
        assertNotNull(since(good, 2_000L, ssid = dash, prefix = false))
        StuckMemory.remember(dash, false, 3_000L)
        assertNotNull(since(good, 4_000L, ssid = "RE_", prefix = true))
        assertNull("a prefix of another family is not", since(good, 5_000L, ssid = "XY_", prefix = true))
    }

    @Test
    fun `a clock that went back does not hold it`() {
        StuckMemory.remember(dash, false, 100_000L)
        assertNull(since(good, 50_000L))
    }

    // ── The watch ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the memory the first good try is the verdict, with no loss and no speech before`() {
        val verdict = StuckDashWatch().failedTry(5_000L, good, remembered = true)
        assertNotNull(verdict)
        assertTrue(verdict!!.remembered)
        assertEquals(1, verdict.tries)
    }

    @Test
    fun `with the memory the verdict is said once, and the dash back takes the notice`() {
        val watch = StuckDashWatch()
        assertNotNull(watch.failedTry(5_000L, good, remembered = true))
        assertNull(watch.failedTry(15_000L, good, remembered = true))
        assertTrue("the notice goes", watch.back())
    }

    @Test
    fun `with the memory, a try that says nothing or finds the dash gone is no verdict`() {
        assertNull(StuckDashWatch().failedTry(5_000L, DashStuck.Sighting.UNKNOWN, remembered = true))
        assertNull(StuckDashWatch().failedTry(5_000L, DashStuck.Sighting.GONE, remembered = true))
    }

    @Test
    fun `without the memory the first good try is no verdict and the count stays as it was`() {
        val watch = StuckDashWatch()
        watch.lost(0L, true)
        assertNull(watch.failedTry(10_000L, good))
        assertNull(watch.failedTry(20_000L, good))
        val verdict = watch.failedTry(30_000L, good)
        assertNotNull(verdict)
        assertEquals(3, verdict!!.tries)
        assertTrue(!verdict.remembered)
        assertNull(StuckDashWatch().failedTry(5_000L, good))
    }

    @Test
    fun `the line for the log says how long ago and that only the ignition clears it`() {
        val line = DashStuck.rememberedLine(47_000L, -58)
        assertTrue(line, line.contains("dash still hung since the last verdict 47 s ago (first try of this start, -58 dBm)"))
        assertTrue(line.contains("only the ignition clears it"))
        assertTrue(!DashStuck.rememberedLine(1_000L, null).contains("dBm"))
    }
}
