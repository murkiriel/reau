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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joystick action "Switch map and music" (P32A), which took the place of "Open the navigation" and "Open the music app", and the three-way change of the list:
 * "Open the phone" and "Switch app" are gone. The page's side of it: what the press hands out, where the rider can pick it, how pages saved with the old names
 * read, that the removed actions are not on the screen's list, and the words in the three languages. What the tap decides is [SwapMapMusicTest].
 */
class SwapMapMusicActionTest {
    private val mediaUp = Slot(Mode.MEDIA, Control.UP, Gesture.ONCE)
    private val mediaLeft = Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE)
    private val mapDown = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)

    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    // ── the action ──

    @Test
    fun `the action is the swap command, with or without Gemini's switch`() {
        assertEquals(AaCommand.SwapMapMusic, Action.SWAP_MAP_MUSIC.command(assistant = false))
        assertEquals(AaCommand.SwapMapMusic, Action.SWAP_MAP_MUSIC.command(assistant = true))
    }

    @Test
    fun `the joystick hands out the command, echoes the legend's name and says so in the log, in both modes`() {
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.SWAP_MAP_MUSIC).with(mapDown, Action.SWAP_MAP_MUSIC)
        val map = JoystickMap(page)
        val media = map.press(DashButtonEvent.VolumeUp(0x06), 0)
        assertEquals(listOf<AaCommand>(AaCommand.SwapMapMusic), media.sent)
        assertEquals(listOf("media up once -> SwapMapMusic"), media.lines)
        assertEquals(EchoLabel.SWAP_MAP_MUSIC, media.echo)
        val mapPress = map.press(DashButtonEvent.Unknown(0x15), 1_000)
        assertEquals(listOf<AaCommand>(AaCommand.SwapMapMusic), mapPress.sent)
        assertEquals(EchoLabel.SWAP_MAP_MUSIC, mapPress.echo)
    }

    @Test
    fun `it is a command for Android Auto, as far as the focus clearer's quiet spell goes`() {
        assertTrue(FocusClearer.toAndroidAuto(listOf(AaCommand.SwapMapMusic)))
    }

    @Test
    fun `it is not a music control, so the music card does not wait for it`() {
        assertFalse(JoystickBindings.DEFAULT.with(mediaUp, Action.SWAP_MAP_MUSIC).mediaControls)
    }

    @Test
    fun `the volume actions stay, as the steps the page still offers`() {
        val names = Action.entries.map { it.name }
        assertTrue(names.containsAll(listOf("VOLUME_UP", "VOLUME_DOWN")))
        assertEquals(AaCommand.Volume(1), Action.VOLUME_UP.command(assistant = false))
        assertEquals(AaCommand.Volume(-1), Action.VOLUME_DOWN.command(assistant = false))
    }

    @Test
    fun `it is on the list of every control, and no control has it by default or on a new install`() {
        for (slot in JoystickBindings.SLOTS) {
            assertTrue("${slot.key} offers it", Action.SWAP_MAP_MUSIC in Action.entries.filter { it.allowedAt(slot) })
            if (!JoystickBindings.isLocked(slot)) {
                assertEquals("${slot.key}: picked", Action.SWAP_MAP_MUSIC, JoystickBindings.DEFAULT.with(slot, Action.SWAP_MAP_MUSIC)[slot])
            }
            assertNotEquals("${slot.key} does not start with it", Action.SWAP_MAP_MUSIC, JoystickBindings.defaultOf(slot))
            assertNotEquals(
                "${slot.key} does not start with it on a new install",
                Action.SWAP_MAP_MUSIC, JoystickBindings.decode(REauSettings.DEFAULT_JOYSTICK)[slot],
            )
        }
        assertFalse(REauSettings.DEFAULT_JOYSTICK, "SWAP_MAP_MUSIC" in REauSettings.DEFAULT_JOYSTICK)
    }

    @Test
    fun `the info ring's switch is still the only one tied to its slot`() {
        // allowedAt answers for the removed actions' successor like for any other: everywhere but the ring's own slot is free of INFO_RING, and the swap is free everywhere.
        for (slot in JoystickBindings.SLOTS) assertTrue(slot.key, Action.SWAP_MAP_MUSIC.allowedAt(slot))
        assertTrue(Action.INFO_RING.allowedAt(JoystickBindings.INFO_RING_SLOT))
        assertFalse(Action.INFO_RING.allowedAt(mediaUp))
    }

    @Test
    fun `a locked control still takes no action, this one included`() {
        val locked = Slot(Mode.MAP, Control.UP, Gesture.ONCE)
        assertEquals(Action.NOTHING, JoystickBindings.DEFAULT.with(locked, Action.SWAP_MAP_MUSIC)[locked])
        assertEquals(Action.NOTHING, JoystickBindings.decode("map.up.once=SWAP_MAP_MUSIC")[locked])
    }

    @Test
    fun `the swap warns on the right side, where the dash's stray press lands`() {
        for (mode in Mode.entries) for (gesture in Gesture.entries) {
            assertTrue(GhostSideWarning.warns(Slot(mode, Control.RIGHT, gesture), Action.SWAP_MAP_MUSIC))
            assertFalse(GhostSideWarning.warns(Slot(mode, Control.LEFT, gesture), Action.SWAP_MAP_MUSIC))
        }
    }

    // ── what is saved ──

    @Test
    fun `it is saved by its name and read back, next to older choices`() {
        val page = JoystickBindings.DEFAULT.with(mediaUp, Action.SWAP_MAP_MUSIC)
        assertEquals("media.up.once=SWAP_MAP_MUSIC", page.encode())
        assertEquals(Action.SWAP_MAP_MUSIC, JoystickBindings.decode("media.up.once=SWAP_MAP_MUSIC")[mediaUp])
        assertEquals(page, JoystickBindings.decode(page.encode()))
        assertEquals(Action.HOME, JoystickBindings.decode("media.up.once=SWAP_MAP_MUSIC;media.left.once=HOME")[mediaLeft])
    }

    @Test
    fun `a page saved with the old action that opened the navigation reads as the swap`() {
        assertEquals(Action.SWAP_MAP_MUSIC, JoystickBindings.decode("media.up.once=OPEN_NAVIGATION")[mediaUp])
        assertEquals(Action.SWAP_MAP_MUSIC, JoystickBindings.decode("map.down.once=OPEN_NAVIGATION")[mapDown])
    }

    @Test
    fun `a page saved with the old action that opened the music reads as the swap`() {
        assertEquals(Action.SWAP_MAP_MUSIC, JoystickBindings.decode("media.up.once=OPEN_MUSIC")[mediaUp])
        assertEquals(Action.SWAP_MAP_MUSIC, JoystickBindings.decode("map.down.once=OPEN_MUSIC")[mapDown])
    }

    @Test
    fun `a page saved with the phone or the switch of apps drops them, and the slot goes back to its default`() {
        for (old in listOf("OPEN_PHONE", "CYCLE_APPS")) {
            val page = JoystickBindings.decode("media.up.once=$old;media.left.once=$old;media.down.once=$old")
            assertEquals(old, JoystickBindings.defaultOf(mediaUp), page[mediaUp])
            assertEquals(old, JoystickBindings.defaultOf(mediaLeft), page[mediaLeft])
            assertEquals(old, JoystickBindings.defaultOf(Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)), page[Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)])
            assertEquals("nothing is kept of them: $old", "", page.encode())
        }
    }

    @Test
    fun `the four old names in one saved page, each read by its own rule, the rest kept`() {
        val saved = "media.up.once=OPEN_NAVIGATION;media.left.once=OPEN_PHONE;media.right.once=OPEN_MUSIC;media.center.once=CYCLE_APPS;" +
            "map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN;media.down.once=VOLUME_UP"
        val page = JoystickBindings.decode(saved)
        assertEquals(Action.SWAP_MAP_MUSIC, page[mediaUp])
        assertEquals("the phone's slot is the default again", Action.WHEEL_PREVIOUS, page[mediaLeft])
        assertEquals(Action.SWAP_MAP_MUSIC, page[Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE)])
        assertEquals("the switch of apps' slot is the default again", Action.CONFIRM, page[Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE)])
        assertEquals(Action.ZOOM_OUT, page[Slot(Mode.MAP, Control.LEFT, Gesture.ONCE)])
        assertEquals(Action.ZOOM_IN, page[Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE)])
        assertEquals(Action.VOLUME_UP, page[Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE)])
        // Written back with the new name, the dropped ones gone, in the page's order.
        assertEquals(
            "media.right.once=SWAP_MAP_MUSIC;media.up.once=SWAP_MAP_MUSIC;media.down.once=VOLUME_UP;map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN",
            page.encode(),
        )
        assertEquals("what is written is read the same", page, JoystickBindings.decode(page.encode()))
    }

    @Test
    fun `the old names are not actions any more, and are never written`() {
        val names = Action.entries.map { it.name }
        for (gone in listOf("OPEN_MUSIC", "OPEN_NAVIGATION", "OPEN_PHONE", "CYCLE_APPS")) assertFalse(gone, gone in names)
        val page = JoystickBindings.decode("media.up.once=OPEN_NAVIGATION;media.left.once=OPEN_MUSIC")
        for (gone in listOf("OPEN_MUSIC", "OPEN_NAVIGATION")) assertFalse(gone, gone in page.encode())
    }

    @Test
    fun `the pages saved before still read as they did`() {
        val before = listOf(
            "NOTHING", "WHEEL_PREVIOUS", "WHEEL_NEXT", "CONFIRM", "BACK", "HOME", "PLAY_PAUSE", "NEXT_SONG", "PREVIOUS_SONG",
            "VOLUME_UP", "VOLUME_DOWN", "ASSISTANT", "ZOOM_IN", "ZOOM_OUT", "INFO_RING",
        )
        assertTrue(Action.entries.map { it.name }.containsAll(before))
        assertEquals("nothing else but the swap, and the four focus arrows are gone", before.size + 1, Action.entries.size)
    }

    // ── the screen's list ──

    @Test
    fun `the removed actions are not on the list the screen offers, and the swap is`() {
        val offered = Action.entries.filter { it.allowedAt(mediaUp) }.map { it.name }
        assertTrue("SWAP_MAP_MUSIC" in offered)
        for (gone in listOf("OPEN_MUSIC", "OPEN_NAVIGATION", "OPEN_PHONE", "CYCLE_APPS")) assertFalse(gone, gone in offered)
        // The screen's own table of names has a row for the swap and none for the removed ones.
        val screen = source("ui/JoystickScreen.kt")
        assertTrue(screen, screen.contains("Action.SWAP_MAP_MUSIC to R.string.joystick_action_swap_map_music"))
        for (gone in listOf("OPEN_MUSIC", "OPEN_NAVIGATION", "OPEN_PHONE", "CYCLE_APPS")) assertFalse("the screen still names $gone", screen.contains(gone))
    }

    @Test
    fun `every action the screen can offer has a name in its table`() {
        val screen = source("ui/JoystickScreen.kt")
        for (action in Action.entries) assertTrue("no row for $action", screen.contains("Action.${action.name} to R.string.joystick_action_"))
    }

    @Test
    fun `the removed code is gone from the app`() {
        assertFalse(File("src/main/java/com/reau/app/aa/AppCycle.kt").exists())
        for (path in listOf("aa/JoystickMap.kt", "aa/JoystickBindings.kt", "aa/AaReceiver.kt", "service/LiveSides.kt", "dash/ActionEcho.kt")) {
            val text = source(path)
            assertFalse("$path still has CycleApps", text.contains("CycleApps"))
            assertFalse("$path still has AppCycle", text.contains("AppCycle") || text.contains("appCycle"))
        }
    }

    // ── the live service ──

    @Test
    fun `the live service reads the screen from the watch, never from the net, and sends what the swap resolves`() {
        val live = source("service/LiveSides.kt")
        val made = live.substringAfter("private val swapMapMusic = com.reau.app.aa.SwapMapMusic(").substringBefore("\n    )")
        assertTrue(made, made.contains("screen = { com.reau.app.aa.AaScreens.watch.screen.value }"))
        assertTrue(made, made.contains("app = { com.reau.app.aa.AaScreens.watch.app.value }"))
        assertTrue(made, made.contains("RideDiagnostics.log(\"dash\", it)"))
        assertTrue(live, live.contains("AaCommand.SwapMapMusic -> receiver?.sendInput(swapMapMusic.resolve())"))
        // ScreenNet's buffers belong to the compositor's thread: nothing outside it classifies.
        assertFalse(source("aa/SwapMapMusic.kt").contains("classify("))
        assertFalse(live.contains("ScreenNet.classify"))
    }

    @Test
    fun `the echo of the swap is the destination's, chosen after the tap was sent`() {
        val live = source("service/LiveSides.kt")
        assertTrue(live, live.contains("com.reau.app.aa.SwapMapMusic.echoFor(named, swapMapMusic.lastTarget)"))
        // The tap is sent before its echo is shown (onButton: send, then echoAndLegend).
        val button = live.substringAfter("session.onButton = { event ->").substringBefore("releaseHeldSide()")
        assertTrue(button, button.indexOf("send(press.sent)") in 0 until button.indexOf("echoAndLegend("))
    }

    @Test
    fun `the receiver never takes the swap for an input`() {
        val receiver = source("aa/AaReceiver.kt")
        assertTrue(receiver, receiver.contains("AaCommand.SwapMapMusic -> return"))
    }

    // ── the words ──

    @Test
    fun `its name on the screen is in the three languages`() {
        val expected = mapOf(
            "values" to "Switch map and music",
            "values-pt" to "Alternar mapa e música",
            "values-es" to "Alternar mapa y música",
        )
        for ((folder, text) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            val found = Regex("""<string name="joystick_action_swap_map_music">(.*?)</string>""").find(strings)
            assertEquals(folder, text, found?.groupValues?.get(1))
        }
    }

    @Test
    fun `the removed actions' names are gone from the three languages`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            for (gone in listOf("joystick_action_open_music", "joystick_action_open_navigation", "joystick_action_open_phone", "joystick_action_cycle_apps")) {
                assertFalse("$folder still has $gone", strings.contains("\"$gone\""))
            }
            for (gone in listOf("echo_open_music", "echo_open_navigation", "echo_open_phone", "echo_cycle_apps")) {
                assertFalse("$folder still has $gone", strings.contains("\"$gone\""))
            }
        }
    }

    @Test
    fun `the legend's name and the two destinations are in the three languages`() {
        val expected = mapOf(
            "values" to Triple("Map/music", "Music", "Map"),
            "values-pt" to Triple("Mapa/música", "Música", "Mapa"),
            "values-es" to Triple("Mapa/música", "Música", "Mapa"),
        )
        for ((folder, words) in expected) {
            val strings = File("src/main/res/$folder/strings.xml").readText()
            fun find(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
            assertEquals("$folder legend", words.first, find("echo_swap_map_music"))
            assertEquals("$folder music", words.second, find("echo_swap_to_music"))
            assertEquals("$folder map", words.third, find("echo_swap_to_map"))
        }
    }

    @Test
    fun `the media and navigation buttons stay declared to Android Auto`() {
        // The swap sends them; the declaration is why Android Auto takes them. The phone button stays declared too, though no action sends it.
        assertEquals(65538, ServiceDiscoveryResponse.KEY_NAVIGATION)
        assertEquals(65537, ServiceDiscoveryResponse.KEY_MEDIA)
        assertTrue(ServiceDiscoveryResponse.KEY_NAVIGATION in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertTrue(ServiceDiscoveryResponse.KEY_MEDIA in ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertEquals(ServiceDiscoveryResponse.SUPPORTED_KEYCODES.distinct(), ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
        assertEquals(listOf(65536, 19, 20, 21, 22, 23, 4, 3, 84, 87, 88, 85, 5, 6, 65538, 65537, 65540), ServiceDiscoveryResponse.SUPPORTED_KEYCODES)
    }
}
