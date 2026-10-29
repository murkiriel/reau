package com.reau.app.service

import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerPolicy
import com.reau.app.aa.ServerResult
import com.reau.app.service.AaServerStarter.StopPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the starter of Android Auto's server decides on its own, with no accessibility service and no phone (the walks need a device). */
class AaServerStarterRulesTest {
    @Test
    fun `the end of a projection switches the server off only when it is the current one, the rider did not switch on again, the switch is on and it was up`() {
        assertEquals(StopPlan.STOP_IT, AaServerStarter.stopPlan(current = true, ligarAfterDesligar = false, switchOn = true, wasUp = true))
        assertEquals("a newer projection began", StopPlan.NEWER_PROJECTION, AaServerStarter.stopPlan(false, false, true, true))
        assertEquals("the rider switched on again", StopPlan.RIDER_AGAIN, AaServerStarter.stopPlan(true, true, true, true))
        assertEquals("the switch is off", StopPlan.SWITCH_OFF, AaServerStarter.stopPlan(true, false, false, true))
        assertEquals("never seen up", StopPlan.NOT_SEEN_UP, AaServerStarter.stopPlan(true, false, true, false))
    }

    @Test
    fun `when several things hold the first one in the order of the rider's interest wins`() {
        assertEquals(StopPlan.NEWER_PROJECTION, AaServerStarter.stopPlan(false, true, false, false))
        assertEquals(StopPlan.RIDER_AGAIN, AaServerStarter.stopPlan(true, true, false, false))
        assertEquals(StopPlan.SWITCH_OFF, AaServerStarter.stopPlan(true, false, false, false))
    }

    @Test
    fun `the stop walk after its wait is made only with no new Ligar and the same projection`() {
        assertTrue(AaServerStarter.stopWalkDue(ligarAfterDesligar = false, current = true))
        assertFalse(AaServerStarter.stopWalkDue(ligarAfterDesligar = true, current = true))
        assertFalse(AaServerStarter.stopWalkDue(ligarAfterDesligar = false, current = false))
        assertFalse(AaServerStarter.stopWalkDue(ligarAfterDesligar = true, current = false))
    }

    @Test
    fun `a Cancelar on the cover counts for eight seconds, not for the eighth`() {
        val cover = AaServerStarter.TAP_COVER_MS
        assertEquals(8_000L, cover)
        assertTrue(AaServerStarter.cancelledAtTap(nowMs = 100_000, cancelledAtMs = 100_000))
        assertTrue(AaServerStarter.cancelledAtTap(nowMs = 100_000 + cover - 1, cancelledAtMs = 100_000))
        assertFalse(AaServerStarter.cancelledAtTap(nowMs = 100_000 + cover, cancelledAtMs = 100_000))
        assertFalse(AaServerStarter.cancelledAtTap(nowMs = 500_000, cancelledAtMs = 100_000))
    }

    @Test
    fun `a walk that was not made leaves the server on after a stop and says what happened after a start`() {
        assertEquals(ServerResult.LEFT_ON, AaServerStarter.notTried(true, ServerResult.LOCKED))
        assertEquals(ServerResult.LEFT_ON, AaServerStarter.notTried(true, ServerResult.INTERRUPTED))
        assertEquals(ServerResult.LOCKED, AaServerStarter.notTried(false, ServerResult.LOCKED))
        assertEquals(ServerResult.INTERRUPTED, AaServerStarter.notTried(false, ServerResult.INTERRUPTED))
    }

    @Test
    fun `a port that stayed closed is followed by a visit, or by the start once more, and by nothing else`() {
        assertEquals(ServerMenuRun.Mode.VISIT, AaServerStarter.modeAfterClosedPort(ServerPolicy.Act.VISIT))
        assertEquals(ServerMenuRun.Mode.START, AaServerStarter.modeAfterClosedPort(ServerPolicy.Act.START_AGAIN))
        for (other in listOf(ServerPolicy.Act.NOTHING, ServerPolicy.Act.START, ServerPolicy.Act.RESTART, ServerPolicy.Act.ANSWERED)) {
            assertNull(other.name, AaServerStarter.modeAfterClosedPort(other))
        }
    }

    @Test
    fun `the phone is locked by the keyguard or by a screen that is not interactive, and not by what is not known`() {
        assertTrue(AaServerStarter.phoneLocked(keyguardLocked = true, interactive = true))
        assertTrue(AaServerStarter.phoneLocked(keyguardLocked = false, interactive = false))
        assertTrue(AaServerStarter.phoneLocked(keyguardLocked = true, interactive = false))
        assertTrue(AaServerStarter.phoneLocked(keyguardLocked = null, interactive = false))
        assertTrue(AaServerStarter.phoneLocked(keyguardLocked = true, interactive = null))
        assertFalse(AaServerStarter.phoneLocked(keyguardLocked = false, interactive = true))
        assertFalse(AaServerStarter.phoneLocked(keyguardLocked = null, interactive = null))
        assertFalse(AaServerStarter.phoneLocked(keyguardLocked = false, interactive = null))
        assertFalse(AaServerStarter.phoneLocked(keyguardLocked = null, interactive = true))
    }
}
