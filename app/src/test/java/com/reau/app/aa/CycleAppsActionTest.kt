package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.protocol.DashButtonEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick action "Switch app (the first 3 of the apps menu)": its press is the command [AaCommand.CycleApps] (the sequence itself is
 * [AppCycle], tested in AppCycleTest), the rider can pick it on any control that takes an action, no control has it by default, it is saved by its
 * name, it does not count as a command for the phone or for the picture, and its name is in the three languages.
 */
class CycleAppsActionTest {
    @Test
    fun `the action is the switch command, with or without Gemini's switch`() {
        assertEquals(AaCommand.CycleApps, Action.CYCLE_APPS.command(assistant = false))
        assertEquals(AaCommand.CycleApps, Action.CYCLE_APPS.command(assistant = true))
    }

    @Test
    fun `the joystick hands out the command and says so in the log, in both modes`() {
        val mediaUp = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
        val mapDown = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.CYCLE_APPS).with(mapDown, Action.CYCLE_APPS)
        val map = JoystickMap(page)
        val media = map.press(DashButtonEvent.VolumeUp(0x06), 0)
        assertEquals(listOf<AaCommand>(AaCommand.CycleApps), media.sent)
        assertEquals(listOf("media up once -> CycleApps"), media.lines)
        assertEquals(listOf<AaCommand>(AaCommand.CycleApps), map.press(DashButtonEvent.Unknown(0x15), 1_000).sent)
    }

    @Test
    fun `a command that goes to Android Auto for the focus clearer's quiet spell, as the keys do`() {
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.CycleApps)))
    }

    @Test
    fun `it is on the list of every control, and no control has it by default or on a new install`() {
        for (slot in JoystickBindings.SLOTS) {
            assertTrue("${slot.key} offers it", Action.CYCLE_APPS in Action.entries.filter { it.allowedAt(slot) })
            if (!JoystickBindings.isLocked(slot)) {
                assertEquals("${slot.key}: picked", Action.CYCLE_APPS, JoystickBindings.DEFAULT.with(slot, Action.CYCLE_APPS)[slot])
            }
            assertTrue("${slot.key} does not start with it", JoystickBindings.defaultOf(slot) != Action.CYCLE_APPS)
            assertTrue(
                "${slot.key} does not start with it on a new install",
                JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)[slot] != Action.CYCLE_APPS,
            )
        }
        assertFalse(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK, "CYCLE_APPS" in com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)
    }

    @Test
    fun `a locked control still takes no action, this one included`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, Action.CYCLE_APPS)[locked])
        assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=CYCLE_APPS")[locked])
    }

    @Test
    fun `it is saved by its name and read back, next to older choices`() {
        val slot = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
        val page = JoystickBindings.DEFAULT.with(slot, Action.CYCLE_APPS)
        assertEquals("media.up.once=CYCLE_APPS", page.encode())
        assertEquals(Action.CYCLE_APPS, JoystickBindings.decode("media.up.once=CYCLE_APPS")[slot])
        assertEquals(page, JoystickBindings.decode(page.encode()))
        assertEquals(
            Action.OPEN_NAVIGATION,
            JoystickBindings.decode("media.up.once=CYCLE_APPS;media.left.once=OPEN_NAVIGATION")[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)],
        )
    }

    @Test
    fun `its name on the screen is in the three languages`() {
        val expected = mapOf(
            "values" to "Switch app (the first 3 of the apps menu)",
            "values-pt" to "Trocar de app (os 3 primeiros do menu de apps)",
            "values-es" to "Cambiar de app (las 3 primeras del menú de apps)",
        )
        for ((folder, text) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            val found = Regex("""<string name="joystick_action_cycle_apps">(.*?)</string>""").find(strings)
            assertEquals(folder, text, found?.groupValues?.get(1))
        }
    }
}
