package com.reau.app.ui

import com.reau.app.aa.AaCommand
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickMap
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick page's "Chamada" tab says what the calls' controls do; JoystickMap does it. A tab that said "Recusa" for a
 * control that did something else would be worse than none, so each cell of the tab is pressed on a real JoystickMap
 * with the dash's own code for it and must send what the tab says.
 */
class CallPageTest {
    /** The code the dash sends for a control in each moment (what the ride log showed). */
    private fun event(moment: CallMoment, control: Control): DashButtonEvent = when (moment) {
        CallMoment.RINGING -> when (control) {
            Control.LEFT -> DashButtonEvent.Unknown(0x02)
            Control.RIGHT -> DashButtonEvent.Unknown(0x01)
            Control.CENTER -> DashButtonEvent.PlayPause(0x05) // ringing, the middle comes as the media middle
            Control.UP -> DashButtonEvent.VolumeUp(0x06)
            Control.DOWN -> DashButtonEvent.VolumeDown(0x07)
        }
        CallMoment.IN_CALL -> when (control) {
            Control.LEFT -> DashButtonEvent.PrevTrack(0x0A) // what the dash would send; it keeps it to itself
            Control.RIGHT -> DashButtonEvent.NextTrack(0x09)
            Control.CENTER -> DashButtonEvent.Unknown(0x16)
            Control.UP -> DashButtonEvent.Unknown(0x03)
            Control.DOWN -> DashButtonEvent.Unknown(0x04)
        }
    }

    private fun sent(moment: CallMoment, control: Control): List<AaCommand> {
        val state = if (moment == CallMoment.RINGING) CallState.RINGING else CallState.IN_CALL
        return JoystickMap(callState = { state }).press(event(moment, control), 10_000).sent
    }

    @Test
    fun `ringing, each cell of the tab is what the joystick sends`() {
        val m = CallMoment.RINGING
        assertEquals(CallEffect.ANSWER, CallPage.effect(m, Control.LEFT))
        assertEquals(listOf(AaCommand.AnswerCall), sent(m, Control.LEFT))
        assertEquals(CallEffect.REJECT, CallPage.effect(m, Control.RIGHT))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.RIGHT))
        assertEquals(CallEffect.REJECT, CallPage.effect(m, Control.CENTER))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.CENTER))
        // up and down are the page's, not the call's
        assertEquals(CallEffect.MODE_PAGE, CallPage.effect(m, Control.UP))
        assertTrue(sent(m, Control.UP).none { it == AaCommand.AnswerCall || it == AaCommand.EndCall || it is AaCommand.CallVolume })
        assertEquals(CallEffect.MODE_PAGE, CallPage.effect(m, Control.DOWN))
        assertTrue(sent(m, Control.DOWN).none { it == AaCommand.AnswerCall || it == AaCommand.EndCall || it is AaCommand.CallVolume })
    }

    @Test
    fun `on a call, each cell of the tab is what the joystick sends`() {
        val m = CallMoment.IN_CALL
        assertEquals(CallEffect.END, CallPage.effect(m, Control.CENTER))
        assertEquals(listOf(AaCommand.EndCall), sent(m, Control.CENTER))
        assertEquals(CallEffect.VOLUME_UP, CallPage.effect(m, Control.UP))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), sent(m, Control.UP))
        assertEquals(CallEffect.VOLUME_DOWN, CallPage.effect(m, Control.DOWN))
        assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), sent(m, Control.DOWN))
        // the sides: the dash does not send them on a call; if one came as a media code, the left would do nothing
        assertEquals(CallEffect.NOT_SENT, CallPage.effect(m, Control.LEFT))
        assertTrue(sent(m, Control.LEFT).isEmpty())
        assertEquals(CallEffect.NOT_SENT, CallPage.effect(m, Control.RIGHT))
    }

    /** The plain codes of the dash's two modes for a control (what it sends when REau does not tell it of the call), the sides and the middle as in the map mode, up the dash's back and down the code 0x15 there. */
    private fun plain(control: Control, media: Boolean): DashButtonEvent = when (control) {
        Control.LEFT -> if (media) DashButtonEvent.PrevTrack(0x0A) else DashButtonEvent.ZoomIn(0x14)
        Control.RIGHT -> if (media) DashButtonEvent.NextTrack(0x09) else DashButtonEvent.ZoomOut(0x13)
        Control.CENTER -> if (media) DashButtonEvent.PlayPause(0x05) else DashButtonEvent.JoystickIn(0x18)
        Control.UP -> if (media) DashButtonEvent.VolumeUp(0x06) else DashButtonEvent.JoystickBack(0x12)
        Control.DOWN -> if (media) DashButtonEvent.VolumeDown(0x07) else DashButtonEvent.Unknown(0x15)
    }

    private fun sentPlain(moment: CallMoment, control: Control, media: Boolean): List<AaCommand> {
        val state = if (moment == CallMoment.RINGING) CallState.RINGING else CallState.IN_CALL
        return JoystickMap(callState = { state }).also { it.callCard = true }.press(plain(control, media), 10_000).sent
    }

    @Test
    fun `with the call card on, each cell of the tab is what the joystick sends by the plain codes of both modes`() {
        for (media in listOf(true, false)) {
            val ringing = CallMoment.RINGING
            assertEquals(CallEffect.ANSWER, CallPage.effect(ringing, Control.LEFT, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.AnswerCall), sentPlain(ringing, Control.LEFT, media))
            assertEquals(CallEffect.REJECT, CallPage.effect(ringing, Control.RIGHT, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.EndCall), sentPlain(ringing, Control.RIGHT, media))
            assertEquals(CallEffect.REJECT, CallPage.effect(ringing, Control.CENTER, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.EndCall), sentPlain(ringing, Control.CENTER, media))
            val onCall = CallMoment.IN_CALL
            assertEquals(CallEffect.NOTHING, CallPage.effect(onCall, Control.LEFT, callCard = true))
            assertTrue(sentPlain(onCall, Control.LEFT, media).isEmpty())
            assertEquals(CallEffect.NOTHING, CallPage.effect(onCall, Control.RIGHT, callCard = true))
            assertTrue("right on a call, media=$media", sentPlain(onCall, Control.RIGHT, media).isEmpty())
            assertEquals(CallEffect.END, CallPage.effect(onCall, Control.CENTER, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.EndCall), sentPlain(onCall, Control.CENTER, media))
        }
        // up and down: nothing while it rings, the call's volume on a call, the same in both modes
        for (media in listOf(true, false)) {
            assertEquals(CallEffect.NOTHING_RINGING, CallPage.effect(CallMoment.RINGING, Control.UP, callCard = true))
            assertTrue("up ringing, media=$media", sentPlain(CallMoment.RINGING, Control.UP, media).isEmpty())
            assertEquals(CallEffect.NOTHING_RINGING, CallPage.effect(CallMoment.RINGING, Control.DOWN, callCard = true))
            assertTrue("down ringing, media=$media", sentPlain(CallMoment.RINGING, Control.DOWN, media).isEmpty())
            assertEquals(CallEffect.VOLUME_UP, CallPage.effect(CallMoment.IN_CALL, Control.UP, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(1)), sentPlain(CallMoment.IN_CALL, Control.UP, media))
            assertEquals(CallEffect.VOLUME_DOWN, CallPage.effect(CallMoment.IN_CALL, Control.DOWN, callCard = true))
            assertEquals(listOf<AaCommand>(AaCommand.CallVolume(-1)), sentPlain(CallMoment.IN_CALL, Control.DOWN, media))
        }
    }

    @Test
    fun `the tab says nothing for the sides with the card on and no tap sent with it off`() {
        for (control in listOf(Control.LEFT, Control.RIGHT)) {
            assertEquals(CallEffect.NOT_SENT, CallPage.effect(CallMoment.IN_CALL, control))
            assertEquals(CallEffect.NOT_SENT, CallPage.effect(CallMoment.IN_CALL, control, callCard = false))
            assertEquals(CallEffect.NOTHING, CallPage.effect(CallMoment.IN_CALL, control, callCard = true))
        }
        // the other cells are the same either way, but ringing's up and down: the page's with the card off, nothing with it on
        for (control in listOf(Control.UP, Control.DOWN)) {
            assertEquals(CallEffect.MODE_PAGE, CallPage.effect(CallMoment.RINGING, control))
            assertEquals(CallEffect.NOTHING_RINGING, CallPage.effect(CallMoment.RINGING, control, callCard = true))
        }
        for (moment in CallMoment.entries) for (control in Control.entries) {
            if (moment == CallMoment.IN_CALL && (control == Control.LEFT || control == Control.RIGHT)) continue
            if (moment == CallMoment.RINGING && (control == Control.UP || control == Control.DOWN)) continue
            assertEquals("$moment $control", CallPage.effect(moment, control), CallPage.effect(moment, control, callCard = true))
        }
    }

    @Test
    fun `every cell has a short label, a sentence and a note`() {
        for (moment in CallMoment.entries) for (control in Control.entries) {
            for (callCard in listOf(false, true)) {
                val effect = CallPage.effect(moment, control, callCard)
                assertTrue("$moment $control", effect.short != 0 && effect.what != 0 && effect.note != 0)
            }
        }
    }

    @Test
    fun `ringing's nothing for up and down has its own note, and the tab reads the call card from the settings`() {
        assertTrue(CallEffect.NOTHING_RINGING.note != CallEffect.NOTHING.note)
        assertEquals(CallEffect.NOTHING.short, CallEffect.NOTHING_RINGING.short)
        val screen = java.io.File("src/main/java/com/reau/app/ui/JoystickScreen.kt").readText()
        assertTrue(screen, "CallPage.effect(moment, it, callCard).short" in screen)
        assertTrue(screen, "CallPage.effect(moment, selected, callCard)" in screen)
        assertTrue(screen, "CallTab(moment, settings.callCard," in screen)
    }
}
