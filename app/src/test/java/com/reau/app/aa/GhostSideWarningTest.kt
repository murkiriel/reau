package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The joystick page's warning "this side sometimes fires together with the center" ([GhostSideWarning]): which slot and action it is for. */
class GhostSideWarningTest {
    @Test
    fun `the warning is for the right, in any mode and gesture, with the costly actions`() {
        // P32A: the swap and the two that open the map or the music app are as expensive by mistake as the opening of either was, so they warn.
        val costly = listOf(Action.SWAP_MAP_MUSIC, Action.OPEN_NAVIGATION, Action.OPEN_MUSIC, Action.ASSISTANT, Action.NEXT_SONG, Action.PREVIOUS_SONG)
        for (mode in Mode.entries) for (gesture in Gesture.entries) {
            val right = Slot(mode, Control.RIGHT, gesture)
            for (action in costly) assertTrue("$right $action", GhostSideWarning.warns(right, action))
            for (action in Action.entries - costly.toSet()) assertFalse("$right $action", GhostSideWarning.warns(right, action))
            for (control in Control.entries - Control.RIGHT) {
                for (action in Action.entries) assertFalse("$control $action", GhostSideWarning.warns(Slot(mode, control, gesture), action))
            }
        }
    }

    @Test
    fun `picking is not stopped, the rule only answers`() {
        val right = Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)
        assertTrue(Action.NEXT_SONG.allowedAt(right))
        assertTrue(GhostSideWarning.warns(right, Action.NEXT_SONG))
        assertTrue(JoystickBindings.DEFAULT.with(right, Action.NEXT_SONG)[right] == Action.NEXT_SONG)
    }
}
