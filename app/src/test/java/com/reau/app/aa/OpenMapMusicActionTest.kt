package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two joystick actions that open one place for good, at the end of the list after "Switch map and music": "Open map" (the last navigation app) and
 * "Open music" (the last music app). They send Android Auto's own key whatever the screen shows; the swap, which looks at the screen first, stays.
 */
class OpenMapMusicActionTest {
    private val mediaUp = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
    private val mapDown = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)

    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `each sends its own key, with or without Gemini's switch`() {
        for (assistant in listOf(false, true)) {
            assertEquals(AaCommand.Key(ServiceDiscoveryResponse.KEY_NAVIGATION), Action.OPEN_NAVIGATION.command(assistant))
            assertEquals(AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA), Action.OPEN_MUSIC.command(assistant))
        }
    }

    @Test
    fun `they send the same keys as the two destinations of the swap`() {
        assertEquals(SwapMapMusic.Target.MAP.keycode, (Action.OPEN_NAVIGATION.command(false) as AaCommand.Key).keycode)
        assertEquals(SwapMapMusic.Target.MUSIC.keycode, (Action.OPEN_MUSIC.command(false) as AaCommand.Key).keycode)
    }

    @Test
    fun `the joystick hands out the key and echoes the destination, in both modes`() {
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.OPEN_NAVIGATION).with(mapDown, Action.OPEN_MUSIC)
        val map = JoystickMap(page)
        val media = map.press(DashButtonEvent.VolumeUp(0x06), 0)
        assertEquals(listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_NAVIGATION)), media.sent)
        assertEquals(EchoLabel.SWAP_TO_MAP, media.echo)
        val mapPress = map.press(DashButtonEvent.Unknown(0x15), 1_000)
        assertEquals(listOf<AaCommand>(AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA)), mapPress.sent)
        assertEquals(EchoLabel.SWAP_TO_MUSIC, mapPress.echo)
    }

    @Test
    fun `the echo is the destination from the start, and the swap's own label never replaces it`() {
        assertEquals(EchoLabel.SWAP_TO_MAP, EchoLabel.of(Action.OPEN_NAVIGATION))
        assertEquals(EchoLabel.SWAP_TO_MUSIC, EchoLabel.of(Action.OPEN_MUSIC))
        assertEquals(EchoLabel.SWAP_MAP_MUSIC, EchoLabel.of(Action.SWAP_MAP_MUSIC))
        // The swap's last destination changes only a label of the swap, so a tap of these two keeps its own.
        assertEquals(EchoLabel.SWAP_TO_MAP, SwapMapMusic.echoFor(EchoLabel.SWAP_TO_MAP, SwapMapMusic.Target.MUSIC))
        assertEquals(EchoLabel.SWAP_TO_MUSIC, SwapMapMusic.echoFor(EchoLabel.SWAP_TO_MUSIC, SwapMapMusic.Target.MAP))
    }

    @Test
    fun `a page saved with the names of before reads the actions themselves, not the swap`() {
        assertEquals(Action.OPEN_NAVIGATION, JoystickBindings.decode("media.up.once=OPEN_NAVIGATION")[mediaUp])
        assertEquals(Action.OPEN_NAVIGATION, JoystickBindings.decode("map.down.once=OPEN_NAVIGATION")[mapDown])
        assertEquals(Action.OPEN_MUSIC, JoystickBindings.decode("media.up.once=OPEN_MUSIC")[mediaUp])
        assertEquals(Action.OPEN_MUSIC, JoystickBindings.decode("map.down.once=OPEN_MUSIC")[mapDown])
    }

    @Test
    fun `they are written and read back as themselves`() {
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.OPEN_MUSIC).with(mapDown, Action.OPEN_NAVIGATION)
        assertEquals("media.up.once=OPEN_MUSIC;map.down.once=OPEN_NAVIGATION", page.encode())
        assertEquals(page, JoystickBindings.decode(page.encode()))
    }

    @Test
    fun `the screen's list ends with them, after the swap and before the info ring where it is offered`() {
        val offered = Action.entries.filter { it.allowedAt(mediaUp) }
        assertEquals(listOf(Action.SWAP_MAP_MUSIC, Action.OPEN_NAVIGATION, Action.OPEN_MUSIC), offered.takeLast(3))
        // The ring's switch is the one action of one slot: where it is offered it comes after them in the enum, as it did after the swap.
        val ringSlot = JoystickBindings.INFO_RING_SLOT
        val ringList = Action.entries.filter { it.allowedAt(ringSlot) }
        assertEquals(listOf(Action.SWAP_MAP_MUSIC, Action.OPEN_NAVIGATION, Action.OPEN_MUSIC, Action.INFO_RING), ringList.takeLast(4))
    }

    @Test
    fun `every control offers them, and none starts with them`() {
        for (slot in JoystickBindings.SLOTS) {
            for (action in listOf(Action.OPEN_NAVIGATION, Action.OPEN_MUSIC)) {
                assertTrue("${slot.key} offers $action", action.allowedAt(slot))
                if (!JoystickBindings.isLocked(slot)) assertEquals(action, JoystickBindings.DEFAULT.with(slot, action)[slot])
                assertFalse("${slot.key} starts with $action", JoystickBindings.defaultOf(slot) == action)
            }
        }
    }

    @Test
    fun `a locked control takes neither`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        for (action in listOf(Action.OPEN_NAVIGATION, Action.OPEN_MUSIC)) {
            assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, action)[locked])
            assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=${action.name}")[locked])
        }
    }

    @Test
    fun `neither is a music control for the card`() {
        assertFalse(JoystickBindings.DEFAULT.with(mediaUp, Action.OPEN_MUSIC).mediaControls)
    }

    @Test
    fun `they warn on the right button, like the swap`() {
        for (mode in Mode.entries) for (action in listOf(Action.OPEN_NAVIGATION, Action.OPEN_MUSIC)) {
            assertTrue(GhostSideWarning.warns(Slot(mode, Control.RIGHT, Gesture.ONCE), action))
            assertFalse(GhostSideWarning.warns(Slot(mode, Control.LEFT, Gesture.ONCE), action))
        }
    }

    @Test
    fun `the screen names them, in the order of the list, in the three languages`() {
        val screen = source("ui/JoystickScreen.kt")
        val swap = screen.indexOf("Action.SWAP_MAP_MUSIC to R.string.joystick_action_swap_map_music")
        val map = screen.indexOf("Action.OPEN_NAVIGATION to R.string.joystick_action_open_map")
        val music = screen.indexOf("Action.OPEN_MUSIC to R.string.joystick_action_open_music")
        assertTrue("$swap $map $music", swap in 0 until map && map < music)
        val expected = mapOf(
            "values" to ("Open map" to "Open music"),
            "values-pt" to ("Abrir mapa" to "Abrir música"),
            "values-es" to ("Abrir mapa" to "Abrir música"),
        )
        for ((folder, words) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            fun find(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
            assertEquals("$folder map", words.first, find("joystick_action_open_map"))
            assertEquals("$folder music", words.second, find("joystick_action_open_music"))
        }
    }
}
