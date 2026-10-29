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
 * The dash's down button in its map mode (0x15) is REau's info ring switch by default, and the rider can give it another action: then the
 * press does that and the ring is no longer switched by it. The switch can be on no other button (only that one sends the code).
 */
class JoystickInfoRingTest {
    private val down = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)

    @Test
    fun `by default down in the map mode is the info ring's switch and nothing is kept`() {
        assertEquals(Action.INFO_RING, JoystickBindings.DEFAULT[down])
        assertTrue(JoystickBindings.DEFAULT.infoRingControl)
        assertEquals("", JoystickBindings.DEFAULT.encode())
        assertEquals("it sends Android Auto nothing", null, Action.INFO_RING.command(assistant = true))
    }

    @Test
    fun `down takes another action, kept, and then the ring is not its switch`() {
        val page = JoystickBindings.DEFAULT.with(down, Action.BACK)
        assertEquals(Action.BACK, page[down])
        assertFalse(page.infoRingControl)
        assertEquals("map.down.once=BACK", page.encode())
        assertEquals(Action.BACK, JoystickBindings.decode(page.encode())[down])
        val back = page.with(down, Action.INFO_RING)
        assertTrue(back.infoRingControl)
        assertEquals("choosing the ring again is the default, not a choice", "", back.encode())
    }

    @Test
    fun `the info ring's switch is on down in the map mode, once, and nowhere else`() {
        for (slot in JoystickBindings.SLOTS.filter { it != down }) {
            assertFalse("$slot", Action.INFO_RING.allowedAt(slot))
            assertEquals("$slot", JoystickBindings.DEFAULT[slot], JoystickBindings.DEFAULT.with(slot, Action.INFO_RING)[slot])
        }
        val page = JoystickBindings.decode("media.down.once=INFO_RING;map.left.once=INFO_RING;map.down.hold=INFO_RING")
        assertEquals("", page.encode())
        assertEquals(Action.HOME, page[Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)])
        assertEquals(Action.WHEEL_PREVIOUS, page[Slot(Mode.MAP, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.NOTHING, page[Slot(Mode.MAP, Control.DOWN, Gesture.HOLD)])
        assertTrue(page.infoRingControl)
    }

    @Test
    fun `every other action can be given to down in the map mode`() {
        for (action in Action.entries.filter { it != Action.INFO_RING }) assertTrue("$action", action.allowedAt(down))
    }
}
