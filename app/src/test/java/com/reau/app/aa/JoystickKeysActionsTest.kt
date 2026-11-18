package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.dash.EchoLabel
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.settings.REauSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick actions that send one of Android Auto's keys (P31 Joy): "Open the music app" (the head unit's MEDIA button, 65537, which opens the
 * last media app used), "Open the phone" (the PHONE button, 65540) and the four focus arrows (the keys up, down, left and right, 19 to 22). The keys are
 * declared to Android Auto, each action sends its key and echoes its name, the rider can pick them on any control that takes an action, no control has
 * them by default, they are saved by name, and their names are in the three languages.
 */
class JoystickKeysActionsTest {
    private val keyed = mapOf(
        Action.OPEN_MUSIC to 65537,
        Action.OPEN_PHONE to 65540,
        Action.FOCUS_UP to 19,
        Action.FOCUS_DOWN to 20,
        Action.FOCUS_LEFT to 21,
        Action.FOCUS_RIGHT to 22,
    )

    private val labels = mapOf(
        Action.OPEN_MUSIC to EchoLabel.OPEN_MUSIC,
        Action.OPEN_PHONE to EchoLabel.OPEN_PHONE,
        Action.FOCUS_UP to EchoLabel.FOCUS_UP,
        Action.FOCUS_DOWN to EchoLabel.FOCUS_DOWN,
        Action.FOCUS_LEFT to EchoLabel.FOCUS_LEFT,
        Action.FOCUS_RIGHT to EchoLabel.FOCUS_RIGHT,
    )

    @Test
    fun `the media and phone buttons are 65537 and 65540 and are declared to Android Auto once each`() {
        assertEquals(65537, ServiceDiscoveryResponse.KEY_MEDIA)
        assertEquals(0x10001, ServiceDiscoveryResponse.KEY_MEDIA)
        assertEquals(65540, ServiceDiscoveryResponse.KEY_TEL)
        assertEquals(0x10004, ServiceDiscoveryResponse.KEY_TEL)
        val declared = ServiceDiscoveryResponse.SUPPORTED_KEYCODES
        assertTrue(65537 in declared)
        assertTrue(65540 in declared)
        for (arrow in 19..22) assertTrue("the arrow $arrow is declared", arrow in declared)
        assertEquals(declared.distinct(), declared)
    }

    @Test
    fun `every key an action sends is one Android Auto was told this head unit has`() {
        for ((action, code) in keyed) {
            assertTrue("$action: $code is declared", code in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        }
    }

    @Test
    fun `each action sends its key, with or without Gemini's switch`() {
        for ((action, code) in keyed) {
            assertEquals("$action", AaCommand.Key(code), action.command(assistant = false))
            assertEquals("$action", AaCommand.Key(code), action.command(assistant = true))
        }
    }

    @Test
    fun `the joystick sends the key and echoes the name when a control has the action, in both modes`() {
        for ((action, code) in keyed) {
            val page = JoystickBindings.DEFAULT.with(Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), action)
                .with(Slot(Mode.MAP, Control.DOWN, Gesture.ONCE), action)
            val media = JoystickMap(page).press(DashButtonEvent.VolumeUp(0x06), 0)
            assertEquals("$action in the media mode", listOf<AaCommand>(AaCommand.Key(code)), media.sent)
            assertEquals("$action echoes", labels.getValue(action), media.echo)
            val map = JoystickMap(page).press(DashButtonEvent.Unknown(0x15), 0)
            assertEquals("$action in the map mode", listOf<AaCommand>(AaCommand.Key(code)), map.sent)
            assertEquals("$action echoes", labels.getValue(action), map.echo)
        }
    }

    @Test
    fun `they are on the list of every control, and no control has them by default or on a new install`() {
        for ((action, _) in keyed) {
            for (slot in JoystickBindings.SLOTS) {
                assertTrue("${slot.key} offers $action", action in Action.entries.filter { it.allowedAt(slot) })
                if (!JoystickBindings.isLocked(slot)) {
                    assertEquals("${slot.key}: picked", action, JoystickBindings.DEFAULT.with(slot, action)[slot])
                }
                assertTrue("${slot.key} does not start with $action", JoystickBindings.defaultOf(slot) != action)
                assertTrue(
                    "${slot.key} does not start with $action on a new install",
                    JoystickBindings.decode(REauSettings.DEFAULT_JOYSTICK)[slot] != action,
                )
            }
            assertFalse(REauSettings.DEFAULT_JOYSTICK, action.name in REauSettings.DEFAULT_JOYSTICK)
        }
    }

    @Test
    fun `a locked control still takes no action, these included`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        for ((action, _) in keyed) {
            assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, action)[locked])
            assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=${action.name}")[locked])
        }
    }

    @Test
    fun `they are saved by name and read back, next to older choices`() {
        val slot = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
        for ((action, _) in keyed) {
            val page = JoystickBindings.DEFAULT.with(slot, action)
            assertEquals("media.up.once=${action.name}", page.encode())
            assertEquals(page, JoystickBindings.decode(page.encode()))
            assertEquals(
                Action.OPEN_NAVIGATION,
                JoystickBindings.decode("media.up.once=${action.name};media.left.once=OPEN_NAVIGATION")[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)],
            )
        }
        assertEquals(setOf("OPEN_MUSIC", "OPEN_PHONE", "FOCUS_UP", "FOCUS_DOWN", "FOCUS_LEFT", "FOCUS_RIGHT"), keyed.keys.map { it.name }.toSet())
    }

    @Test
    fun `the music app and the phone warn on the right side, the arrows do not`() {
        val right = Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)
        assertTrue(GhostSideWarning.warns(right, Action.OPEN_MUSIC))
        assertTrue(GhostSideWarning.warns(right, Action.OPEN_PHONE))
        for (action in listOf(Action.FOCUS_UP, Action.FOCUS_DOWN, Action.FOCUS_LEFT, Action.FOCUS_RIGHT)) {
            assertFalse("$action", GhostSideWarning.warns(right, action))
        }
        assertFalse(GhostSideWarning.warns(Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE), Action.OPEN_MUSIC))
    }

    @Test
    fun `the names on the screen and in the echo are in the three languages`() {
        val screen = mapOf(
            "values" to mapOf(
                "open_music" to "Open the music app (the last one used)", "open_phone" to "Open the phone", "focus_up" to "Focus up",
                "focus_down" to "Focus down", "focus_left" to "Focus left", "focus_right" to "Focus right",
            ),
            "values-pt" to mapOf(
                "open_music" to "Abrir o app de música (o último usado)", "open_phone" to "Abrir o telefone", "focus_up" to "Foco para cima",
                "focus_down" to "Foco para baixo", "focus_left" to "Foco para a esquerda", "focus_right" to "Foco para a direita",
            ),
            "values-es" to mapOf(
                "open_music" to "Abrir la app de música (la última usada)", "open_phone" to "Abrir el teléfono", "focus_up" to "Foco hacia arriba",
                "focus_down" to "Foco hacia abajo", "focus_left" to "Foco a la izquierda", "focus_right" to "Foco a la derecha",
            ),
        )
        val echo = mapOf(
            "values" to mapOf(
                "open_music" to "Open music", "open_phone" to "Open phone", "focus_up" to "Focus up",
                "focus_down" to "Focus down", "focus_left" to "Focus left", "focus_right" to "Focus right",
            ),
            "values-pt" to mapOf(
                "open_music" to "Abrir música", "open_phone" to "Abrir telefone", "focus_up" to "Foco acima",
                "focus_down" to "Foco abaixo", "focus_left" to "Foco à esquerda", "focus_right" to "Foco à direita",
            ),
            "values-es" to mapOf(
                "open_music" to "Abrir música", "open_phone" to "Abrir teléfono", "focus_up" to "Foco arriba",
                "focus_down" to "Foco abajo", "focus_left" to "Foco izquierda", "focus_right" to "Foco derecha",
            ),
        )
        for (folder in screen.keys) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            for ((name, text) in screen.getValue(folder)) {
                assertEquals("$folder $name", text, Regex("""<string name="joystick_action_$name">(.*?)</string>""").find(strings)?.groupValues?.get(1))
            }
            for ((name, text) in echo.getValue(folder)) {
                assertEquals("$folder echo $name", text, Regex("""<string name="echo_$name">(.*?)</string>""").find(strings)?.groupValues?.get(1))
            }
        }
    }
}
