package com.reau.app.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's call as the joystick needs it, to answer calls by the joystick. */
class PhoneCallsTest {
    @Test
    fun `ringing wins over a call already going on, nothing is idle`() {
        assertEquals(CallState.RINGING, PhoneCalls.stateOf(ringing = true, inCall = true))
        assertEquals(CallState.RINGING, PhoneCalls.stateOf(ringing = true, inCall = false))
        assertEquals(CallState.IN_CALL, PhoneCalls.stateOf(ringing = false, inCall = true))
        assertEquals(CallState.IDLE, PhoneCalls.stateOf(ringing = false, inCall = false))
    }

    @Test
    fun `with the switch off or without the permissions the joystick never sees a call`() {
        assertEquals(CallState.IDLE, PhoneCalls.visibleState(switchOn = false, permitted = true, CallState.RINGING))
        assertEquals(CallState.IDLE, PhoneCalls.visibleState(switchOn = true, permitted = false, CallState.RINGING))
        assertEquals(CallState.RINGING, PhoneCalls.visibleState(switchOn = true, permitted = true, CallState.RINGING))
        assertEquals(CallState.IN_CALL, PhoneCalls.visibleState(switchOn = true, permitted = true, CallState.IN_CALL))
    }

    @Test
    fun `an end that Telecom answers false after the key to Android Auto does not read as a failure`() {
        assertEquals(
            "end by the joystick: Telecom.endCall returned false (call state before: IDLE; the call may already have ended by the key sent to Android Auto)",
            PhoneCalls.endLine(ended = false, before = CallState.IDLE),
        )
        assertEquals(
            "end by the joystick: Telecom.endCall returned false (call state before: IN_CALL; the call may already have ended by the key sent to Android Auto)",
            PhoneCalls.endLine(ended = false, before = CallState.IN_CALL),
        )
    }

    @Test
    fun `an end that Telecom did is told with the state before`() {
        assertEquals(
            "end by the joystick: Telecom.endCall returned true (call state before: RINGING)",
            PhoneCalls.endLine(ended = true, before = CallState.RINGING),
        )
    }
}
