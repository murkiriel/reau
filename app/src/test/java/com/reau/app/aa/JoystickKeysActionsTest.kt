package com.reau.app.aa

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.settings.REauSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys Android Auto is told this head unit has (P31 Joy) and the four focus arrows ("Focus up", "Focus down", "Focus left", "Focus right") that were
 * actions of the joystick and are gone (P32S, the rider's say): a page saved with one of them reads as the slot's default, and nothing of them is written back.
 * The media and phone buttons stay declared to Android Auto (the swap of the map and the music sends the media one).
 */
class JoystickKeysActionsTest {
    private val gone = listOf("FOCUS_UP", "FOCUS_DOWN", "FOCUS_LEFT", "FOCUS_RIGHT")

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
    fun `the focus arrows are not actions any more`() {
        val names = Action.entries.map { it.name }
        for (name in gone) assertFalse(name, name in names)
    }

    @Test
    fun `a page saved with a focus arrow drops it, and the slot goes back to its default`() {
        for (old in gone) {
            val page = JoystickBindings.decode("media.up.once=$old;media.left.once=$old;media.down.once=$old;map.down.once=$old")
            for (slot in listOf(
                Slot(Mode.MEDIA, Control.UP, Gesture.ONCE), Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE),
                Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE), Slot(Mode.MAP, Control.DOWN, Gesture.ONCE),
            )) {
                assertEquals("$old at ${slot.key}", JoystickBindings.defaultOf(slot), page[slot])
            }
            assertEquals("nothing is kept of it: $old", "", page.encode())
        }
    }

    @Test
    fun `the rest of a saved page is kept next to a focus arrow, and is read the same when written back`() {
        val saved = "media.up.once=FOCUS_UP;media.left.once=PLAY_PAUSE;media.right.once=FOCUS_RIGHT;media.center.once=NEXT_SONG;map.left.once=ZOOM_OUT"
        val page = JoystickBindings.decode(saved)
        assertEquals(Action.NOTHING, page[Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)])
        assertEquals(Action.PLAY_PAUSE, page[Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.WHEEL_NEXT, page[Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)])
        assertEquals(Action.NEXT_SONG, page[Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE)])
        assertEquals(Action.ZOOM_OUT, page[Slot(Mode.MAP, Control.LEFT, Gesture.ONCE)])
        assertEquals("media.left.once=PLAY_PAUSE;media.center.once=NEXT_SONG;map.left.once=ZOOM_OUT", page.encode())
        assertEquals(page, JoystickBindings.decode(page.encode()))
        for (name in gone) assertFalse(name, name in REauSettings.DEFAULT_JOYSTICK)
    }

    @Test
    fun `the screen and the echo have nothing of the focus arrows, in the three languages`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder: a focus name is left", Regex("""name="(joystick_action|echo)_focus_""").containsMatchIn(strings))
        }
        for (path in listOf("ui/JoystickScreen.kt", "dash/ActionEcho.kt", "aa/JoystickBindings.kt")) {
            val code = File("src/main/java/com/reau/app/$path").readText()
            for (name in gone) assertFalse("$path still has $name", name in code.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n"))
        }
    }
}
