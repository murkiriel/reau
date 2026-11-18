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
 * The joystick action "Open navigation (Waze or Maps)": Android Auto's own NAVIGATION button (65538, 0x10002), which opens the last navigation
 * app used, full screen, from any screen. The key is declared to Android Auto (only it: not the media or phone buttons), the action sends it,
 * the rider can pick it on any control that takes an action, no control has it by default, and pages saved before it existed read as they did.
 */
class OpenNavigationActionTest {
    @Test
    fun `the navigation button is declared to Android Auto as 65538, next to the media and phone buttons`() {
        assertEquals(65538, ServiceDiscoveryResponse.KEY_NAVIGATION)
        assertEquals(0x10002, ServiceDiscoveryResponse.KEY_NAVIGATION)
        assertTrue(ServiceDiscoveryResponse.KEY_NAVIGATION in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        // Changed on purpose (P31 Joy): the media (0x10001) and phone (0x10004) buttons are asked for too, for "open the music app" and "open the phone".
        assertTrue("media (0x10001) is asked for", 65537 in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertTrue("phone (0x10004) is asked for", 65540 in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertEquals("the keys declared before are still declared, once each", ServiceDiscoveryResponse.SUPPORTED_KEYCODES.distinct(), ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertEquals(listOf(65536, 19, 20, 21, 22, 23, 4, 3, 84, 87, 88, 85, 5, 6, 65538, 65537, 65540), ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
    }

    @Test
    fun `the action sends the navigation button, with or without Gemini's switch`() {
        assertEquals(AaCommand.Key(65538), Action.OPEN_NAVIGATION.command(assistant = false))
        assertEquals(AaCommand.Key(65538), Action.OPEN_NAVIGATION.command(assistant = true))
    }

    @Test
    fun `the joystick sends the key when a control has the action, in both modes`() {
        val mediaUp = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
        val mapDown = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.OPEN_NAVIGATION).with(mapDown, Action.OPEN_NAVIGATION)
        val map = JoystickMap(page)
        assertEquals(listOf<AaCommand>(AaCommand.Key(65538)), map.press(DashButtonEvent.VolumeUp(0x06), 0).sent)
        assertEquals(listOf<AaCommand>(AaCommand.Key(65538)), map.press(DashButtonEvent.Unknown(0x15), 1_000).sent)
    }

    @Test
    fun `it is on the list of every control (the screen offers the list to the ones not locked), and no control has it by default`() {
        for (slot in JoystickBindings.SLOTS) {
            val offered = Action.entries.filter { it.allowedAt(slot) }
            assertTrue("${slot.key} offers it", Action.OPEN_NAVIGATION in offered)
            if (!JoystickBindings.isLocked(slot)) {
                assertEquals("${slot.key}: picked", Action.OPEN_NAVIGATION, JoystickBindings.DEFAULT.with(slot, Action.OPEN_NAVIGATION)[slot])
            }
            assertTrue("${slot.key} does not start with it", JoystickBindings.defaultOf(slot) != Action.OPEN_NAVIGATION)
            assertTrue("${slot.key} does not start with it on a new install", JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)[slot] != Action.OPEN_NAVIGATION)
        }
        assertFalse(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK, "OPEN_NAVIGATION" in com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)
    }

    @Test
    fun `a locked control still takes no action, this one included`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, Action.OPEN_NAVIGATION)[locked])
        assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=OPEN_NAVIGATION")[locked])
    }

    @Test
    fun `the name is what is saved, so pages saved before the action existed read as they did`() {
        // Saved by name, never by position: the new action sits among the others in the list, and the old names are all still there.
        val before = listOf(
            "NOTHING", "WHEEL_PREVIOUS", "WHEEL_NEXT", "CONFIRM", "BACK", "HOME", "PLAY_PAUSE", "NEXT_SONG", "PREVIOUS_SONG",
            "ASSISTANT", "ZOOM_IN", "ZOOM_OUT", "INFO_RING",
        )
        assertTrue(Action.entries.map { it.name }.containsAll(before))
        val saved = "media.left.once=HOME;media.up.once=ASSISTANT;media.down.once=PLAY_PAUSE;media.center.once=NEXT_SONG;" +
            "map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN;map.down.once=INFO_RING"
        val page = JoystickBindings.decode(saved)
        assertEquals(Action.HOME, page[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.ASSISTANT, page[Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)])
        assertEquals(Action.PLAY_PAUSE, page[Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)])
        assertEquals(Action.NEXT_SONG, page[Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE)])
        assertEquals(Action.ZOOM_OUT, page[Slot(Mode.MAP, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.ZOOM_IN, page[Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE)])
        assertEquals(Action.INFO_RING, page[JoystickBindings.INFO_RING_SLOT])
        // The info ring's slot is fixed by the code (INFO_RING_SLOT), not chosen, so it is read back but not written.
        assertEquals("and it is written back as it was", saved.removeSuffix(";map.down.once=INFO_RING"), page.encode())
    }

    @Test
    fun `the new action is saved by its name and read back`() {
        val slot = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
        val page = JoystickBindings.DEFAULT.with(slot, Action.OPEN_NAVIGATION)
        assertEquals("media.up.once=OPEN_NAVIGATION", page.encode())
        assertEquals(Action.OPEN_NAVIGATION, JoystickBindings.decode("media.up.once=OPEN_NAVIGATION")[slot])
        assertEquals(page, JoystickBindings.decode(page.encode()))
        assertEquals("a saved page with it, next to older choices", Action.HOME,
            JoystickBindings.decode("media.up.once=OPEN_NAVIGATION;media.left.once=HOME")[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)])
    }

    @Test
    fun `its name on the screen is in the three languages`() {
        val expected = mapOf(
            "values" to "Open navigation (Waze or Maps)",
            "values-pt" to "Abrir a navegação (Waze ou Maps)",
            "values-es" to "Abrir la navegación (Waze o Maps)",
        )
        for ((folder, text) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            val found = Regex("""<string name="joystick_action_open_navigation">(.*?)</string>""").find(strings)
            assertEquals(folder, text, found?.groupValues?.get(1))
        }
    }
}
