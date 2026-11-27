package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The idle lock: when the count runs, how long it is, what a touch does, and the two that share the window's keep-on flag. Pure, no Android. */
class IdleLockTest {
    private fun active(
        switchOn: Boolean = true, phase: Phase = Phase.RUNNING, dash: DashLink = DashLink.READY, resumed: Boolean = true, lockActive: Boolean = false,
    ) = IdleLock.active(switchOn, phase, dash, resumed, lockActive)

    @Test
    fun `the count runs with the switch on, the projection running, the dash ready, REau in front and its lock not on`() {
        assertTrue(active())
        assertNull(IdleLock.offReason(true, Phase.RUNNING, DashLink.READY, resumed = true, lockActive = false))
    }

    @Test
    fun `any one condition missing stops it, and the reason says which`() {
        assertEquals("switch", IdleLock.offReason(false, Phase.RUNNING, DashLink.READY, true, false))
        assertEquals("projection", IdleLock.offReason(true, Phase.STOPPED, DashLink.READY, true, false))
        assertEquals("projection", IdleLock.offReason(true, Phase.PREPARING, DashLink.READY, true, false))
        for (dash in DashLink.entries.filter { it != DashLink.READY }) assertEquals("dash $dash", "dash", IdleLock.offReason(true, Phase.RUNNING, dash, true, false))
        assertEquals("left", IdleLock.offReason(true, Phase.RUNNING, DashLink.READY, resumed = false, lockActive = false))
        assertEquals("locked", IdleLock.offReason(true, Phase.RUNNING, DashLink.READY, resumed = true, lockActive = true))
        assertFalse(active(switchOn = false))
        assertFalse(active(resumed = false))
        assertFalse(active(lockActive = true))
    }

    @Test
    fun `it is the button's own condition, the switch, the running phase and the dash ready`() {
        for (phase in Phase.entries) for (dash in DashLink.entries) {
            assertEquals("$phase $dash", ScreenLock.buttonShown(true, phase, dash), active(phase = phase, dash = dash))
        }
    }

    @Test
    fun `the count is the system's screen timeout, not under 15 s and not over 10 min`() {
        assertEquals(30_000L, IdleLock.timeoutMs(30_000))
        assertEquals(60_000L, IdleLock.timeoutMs(60_000))
        assertEquals("a few seconds takes the floor", 15_000L, IdleLock.timeoutMs(5_000))
        assertEquals(15_000L, IdleLock.timeoutMs(15_000))
        assertEquals(600_000L, IdleLock.timeoutMs(600_000))
        assertEquals("long takes the ceiling", 600_000L, IdleLock.timeoutMs(1_800_000))
        assertEquals("never (zero, negative or the biggest Int) takes the ceiling", 600_000L, IdleLock.timeoutMs(0))
        assertEquals(600_000L, IdleLock.timeoutMs(-1))
        assertEquals(600_000L, IdleLock.timeoutMs(Int.MAX_VALUE))
        assertEquals(30_000, IdleLock.DEFAULT_SYSTEM_MS)
    }

    @Test
    fun `a touch begins the count again, and without one it runs out at the timeout`() {
        val count = IdleLock.Count(timeoutMs = 30_000L, startMs = 1_000L)
        assertEquals(30_000L, count.msUntilExpiry(1_000L))
        assertFalse(count.expired(30_999L))
        assertEquals(1L, count.msUntilExpiry(30_999L))
        assertTrue("it runs out 30 s after the start", count.expired(31_000L))
        assertEquals(0L, count.msUntilExpiry(99_000L))
        count.touch(20_000L)
        assertEquals("the touch restarted it", 30_000L, count.msUntilExpiry(20_000L))
        assertFalse(count.expired(49_999L))
        assertTrue(count.expired(50_000L))
        count.touch(45_000L)
        assertFalse("another touch pushes it again", count.expired(50_000L))
        assertTrue(count.expired(75_000L))
    }

    @Test
    fun `the lines say the timeout in seconds and why the count stopped`() {
        assertEquals("idle timer on timeout=30s", IdleLock.onLine(30_000L))
        assertEquals("idle timer on timeout=600s", IdleLock.onLine(IdleLock.MAX_TIMEOUT_MS))
        assertEquals("idle timer off reason=dash", IdleLock.offLine("dash"))
    }

    @Test
    fun `the flag is set by the first that wants it and cleared by the last that lets go, so neither takes the other's away`() {
        val keep = IdleLock.KeepSources()
        assertTrue("the idle count is the first", keep.want("idle"))
        assertFalse("the lock joins it", keep.want("lock"))
        assertFalse("the idle count lets go: the lock still wants it", keep.release("idle"))
        assertTrue("the lock is the last", keep.release("lock"))
        assertFalse("nothing to let go", keep.release("lock"))
        assertTrue(keep.want("lock"))
        assertFalse(keep.want("idle"))
        assertFalse(keep.release("lock"))
        assertTrue(keep.release("idle"))
        assertFalse("wanting twice is one", IdleLock.KeepSources().also { it.want("idle") }.want("idle"))
    }
}
