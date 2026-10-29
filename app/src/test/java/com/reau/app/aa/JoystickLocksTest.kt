package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick page: in the dash's map mode, up is the dash's own back and the centre puts it in the media mode, so the two are locked: no
 * action, whatever was saved for them before. Down is REau's info ring by default and can be given another action ([JoystickInfoRingTest]).
 */
class JoystickLocksTest {
    @Test
    fun `up and the centre are locked in the map mode only`() {
        for (gesture in Gesture.entries) {
            assertTrue(JoystickBindings.isLocked(Slot(Mode.MAP, Control.UP, gesture)))
            assertTrue(JoystickBindings.isLocked(Slot(Mode.MAP, Control.CENTER, gesture)))
        }
        for (control in listOf(Control.LEFT, Control.RIGHT, Control.DOWN)) assertFalse(JoystickBindings.isLocked(Slot(Mode.MAP, control, Gesture.ONCE)))
        for (control in Control.entries) assertFalse(JoystickBindings.isLocked(Slot(Mode.MEDIA, control, Gesture.ONCE)))
    }

    @Test
    fun `a choice saved for a locked control does nothing and is not kept`() {
        val page = JoystickBindings.decode("map.center.once=HOME;map.up.once=BACK")
        assertEquals(Action.NOTHING, page[Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)])
        assertEquals(Action.NOTHING, page[Slot(Mode.MAP, Control.UP, Gesture.ONCE)])
        assertEquals("", page.encode())
    }

    @Test
    fun `a page built with a locked choice still does nothing there`() {
        val centre = Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings(mapOf(centre to Action.HOME))[centre])
    }

    @Test
    fun `picking an action for a locked control changes nothing`() {
        val centre = Slot(Mode.MAP, Control.CENTER, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(centre, Action.ASSISTANT)[centre])
        assertEquals("", JoystickBindings.DEFAULT.with(centre, Action.ASSISTANT).encode())
    }
}
