package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.ActionEcho
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.phone.CallState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick actions "Volume up" and "Volume down" (P32 Joy): their command, where the rider can pick them (any control that takes an action, none by
 * default, none on a new install), how they are saved, what the joystick says of their tap, that they are not music controls for the card, and their words
 * in the three languages. The step itself, and the stream it moves, are [com.reau.app.phone.PhoneVolume] (PhoneVolumeTest).
 */
class VolumeActionsTest {
    private val mediaUpSlot = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
    private val mediaDownSlot = Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)
    private val mediaUp = DashButtonEvent.VolumeUp(0x06)
    private val mediaDown = DashButtonEvent.VolumeDown(0x07)
    private val volume = listOf(Action.VOLUME_UP, Action.VOLUME_DOWN)

    @Test
    fun `the actions are a step up and a step down of the phone's volume, with or without Gemini's switch`() {
        for (assistant in listOf(false, true)) {
            assertEquals(AaCommand.Volume(1), Action.VOLUME_UP.command(assistant))
            assertEquals(AaCommand.Volume(-1), Action.VOLUME_DOWN.command(assistant))
        }
    }

    @Test
    fun `the dash's own up and down of the media mode can be the volume, as the dash calls them`() {
        val page = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP).with(mediaDownSlot, Action.VOLUME_DOWN)
        val map = JoystickMap(page)
        val up = map.press(mediaUp, 0)
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), up.sent)
        assertEquals(listOf("media up once -> Volume(step=1)"), up.lines)
        val down = map.press(mediaDown, 5_000)
        assertEquals(listOf<AaCommand>(AaCommand.Volume(-1)), down.sent)
        assertEquals(listOf("media down once -> Volume(step=-1)"), down.lines)
    }

    @Test
    fun `a volume tap is echoed by its name, and the legend says it`() {
        val page = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP).with(mediaDownSlot, Action.VOLUME_DOWN)
        val map = JoystickMap(page)
        assertEquals(EchoLabel.VOLUME_UP, map.press(mediaUp, 0).echo)
        assertEquals(EchoLabel.VOLUME_DOWN, map.press(mediaDown, 5_000).echo)
        val legend = map.legend(Mode.MEDIA).toMap()
        assertEquals(EchoLabel.VOLUME_UP, legend[Control.UP])
        assertEquals(EchoLabel.VOLUME_DOWN, legend[Control.DOWN])
        assertEquals(EchoLabel.VOLUME_UP, EchoLabel.of(Action.VOLUME_UP))
        assertEquals(EchoLabel.VOLUME_DOWN, EchoLabel.of(Action.VOLUME_DOWN))
    }

    @Test
    fun `during a call up and down still go through the page, so the volume stays within reach`() {
        // The stream is chosen when the step is taken (PhoneVolume.streamFor); the joystick hands the same command over.
        val page = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP)
        val map = JoystickMap(page, callState = { CallState.IN_CALL })
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), map.press(mediaUp, 0).sent)
    }

    @Test
    fun `the music mode keeps up and down as the page's, so the volume works with Spotify in front`() {
        val page = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP).with(mediaDownSlot, Action.VOLUME_DOWN)
        val map = JoystickMap(page)
        map.musicActive = { true }
        assertEquals(listOf<AaCommand>(AaCommand.Volume(1)), map.press(mediaUp, 0).sent)
        assertEquals(listOf<AaCommand>(AaCommand.Volume(-1)), map.press(mediaDown, 5_000).sent)
    }

    @Test
    fun `a volume step is not for Android Auto, so it does not start the focus clearer's quiet spell`() {
        assertFalse(FocusClearer.toAndroidAuto(listOf(AaCommand.Volume(1))))
        assertFalse(FocusClearer.toAndroidAuto(listOf(AaCommand.Volume(-1))))
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.Volume(1), AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER))))
    }

    @Test
    fun `they are on the list of every control that takes an action, and none starts with them`() {
        for (slot in JoystickBindings.SLOTS) {
            for (action in volume) {
                assertTrue("${slot.key} allows $action", action.allowedAt(slot))
                assertTrue("${slot.key} offers $action", action in Action.entries.filter { it.allowedAt(slot) })
                if (!JoystickBindings.isLocked(slot)) assertEquals("${slot.key}: picked", action, JoystickBindings.DEFAULT.with(slot, action)[slot])
                assertNotEquals("${slot.key} does not start with $action", action, JoystickBindings.defaultOf(slot))
                assertNotEquals(
                    "${slot.key} does not start with $action on a new install", action,
                    JoystickBindings.decode(com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)[slot],
                )
            }
        }
        for (action in volume) assertFalse(action.name in com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK)
    }

    @Test
    fun `a locked control still takes no action, these included`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        for (action in volume) {
            assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, action)[locked])
            assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=${action.name}")[locked])
        }
    }

    @Test
    fun `they are saved by name and read back, next to older choices`() {
        val page = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP).with(mediaDownSlot, Action.VOLUME_DOWN)
        assertEquals("media.up.once=VOLUME_UP;media.down.once=VOLUME_DOWN", page.encode())
        assertEquals(page, JoystickBindings.decode(page.encode()))
        assertEquals(
            Action.OPEN_NAVIGATION,
            JoystickBindings.decode("media.up.once=VOLUME_UP;media.left.once=OPEN_NAVIGATION")[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)],
        )
        assertEquals("a page with the old names is read as before", Action.NEXT_SONG, JoystickBindings.decode("media.up.once=NEXT_SONG")[mediaUpSlot])
    }

    @Test
    fun `the volume is not a music control, so the music card does not wait for it`() {
        val onlyVolume = JoystickBindings.DEFAULT.with(mediaUpSlot, Action.VOLUME_UP).with(mediaDownSlot, Action.VOLUME_DOWN)
        assertFalse(onlyVolume.mediaControls)
        assertTrue(onlyVolume.with(Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE), Action.PLAY_PAUSE).mediaControls)
    }

    @Test
    fun `the volume does not warn on the right button, a step is cheap to take back`() {
        val right = Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)
        for (action in volume) assertFalse(GhostSideWarning.warns(right, action))
    }

    @Test
    fun `their names on the screen are in the three languages`() {
        val expected = mapOf(
            "values" to ("Volume up" to "Volume down"),
            "values-pt" to ("Aumentar volume" to "Diminuir volume"),
            "values-es" to ("Subir volumen" to "Bajar volumen"),
        )
        for ((folder, names) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            fun text(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
            assertEquals(folder, names.first, text("joystick_action_volume_up"))
            assertEquals(folder, names.second, text("joystick_action_volume_down"))
        }
    }

    @Test
    fun `the level the title says fits the title in each language, with the most digits a stream may have`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            for (name in listOf("volume_echo_level", "volume_echo_call")) {
                val format = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
                assertTrue("$folder $name exists", format != null)
                val said = String.format(java.util.Locale.ROOT, format!!, 100, 100)
                assertTrue("$folder $name '$said' holds ${ActionEcho.LABEL_MAX}", said.length <= ActionEcho.LABEL_MAX)
                assertEquals("$folder $name: the level and the most, as the number asks", "${format.substringBefore('%')}12/30", String.format(java.util.Locale.ROOT, format, 12, 30))
            }
        }
    }
}
