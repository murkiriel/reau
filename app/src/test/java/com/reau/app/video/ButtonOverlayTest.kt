package com.reau.app.video

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.dash.protocol.DashButtonEvent
import com.reau.app.service.LiveTuning
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsInUse
import com.reau.app.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code of the last dash button written on the dash's video, behind a debug switch. */
class ButtonOverlayTest {
    @Test
    fun `the code is in hex with two digits and the name is the one REau knows the button by`() {
        val center = ButtonOverlay.textFor(DashButtonEvent.JoystickIn(0x18), "map center -> Key(keycode=23)")
        assertEquals("0x18", center.code)
        assertEquals("JoystickIn", center.name)
        assertEquals("map center -> Key(keycode=23)", center.detail)
        assertEquals("0x18  JoystickIn", center.first)
        assertEquals("0x05", ButtonOverlay.textFor(DashButtonEvent.JoystickIn(0x05)).code)
        assertEquals("0x0A", ButtonOverlay.textFor(DashButtonEvent.JoystickIn(0x0A)).code)
        assertEquals("", ButtonOverlay.textFor(DashButtonEvent.JoystickIn(0x05)).detail)
    }

    @Test
    fun `the detail is broken where it says -- and then at a space, in lines the round glass does not cut`() {
        assertEquals(listOf("map down once", "Key(keycode=23)"), ButtonText("0x15", "Unknown", "map down once -> Key(keycode=23)").detailLines)
        assertEquals(emptyList<String>(), ButtonText("0x15", "Unknown").detailLines)
        val long = ButtonText("0x09", "NextTrack", "media right once -> Scroll(delta=1), Key(keycode=22) and more words after").detailLines
        assertTrue(long.toString(), long.size >= 3)
        for (line in long) assertTrue(line, line.length <= ButtonText.LINE_CHARS)
        assertEquals("nothing is lost", "media right once Scroll(delta=1), Key(keycode=22) and more words after".replace(" ", ""), long.joinToString("").replace(" ", ""))
    }

    @Test
    fun `the note says whether the maneuver ring was shown or hidden, and why`() {
        assertEquals("maneuver ring hidden: joystick 0x05", ButtonText.ringNote(false, "joystick 0x05"))
        assertEquals("maneuver ring shown: screen NAV", ButtonText.ringNote(true, "screen NAV"))
    }

    @Test
    fun `it is off by default, with a place under the stats text that is on the glass`() {
        val s = REauSettings()
        assertFalse(s.buttonOverlay)
        assertFalse(s.buttonLook.on)
        assertTrue("under the stats text", s.buttonY > s.statsY)
        assertTrue(DashGlass.isVisible(s.buttonX.toDouble(), s.buttonY.toDouble()))
        assertTrue(DashGlass.isVisible(s.buttonX + 200.0, s.buttonY + s.buttonSize.toDouble()))
    }

    @Test
    fun `the switch, the place and the size are kept when saved`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(buttonOverlay = true, buttonX = 30, buttonY = 130, buttonSize = 40))
        val back = SettingsStore(prefs).load()
        assertEquals(true, back.buttonOverlay)
        assertEquals(ButtonOverlay(com.reau.app.BuildConfig.DEBUG, 30, 130, 40), back.buttonLook)
    }

    @Test
    fun `the place and the size stay within the dash's frame and what is offered`() {
        val s = REauSettings(buttonX = 9999, buttonY = -5, buttonSize = 1).sanitized()
        assertEquals(DashGlass.FRAME_W, s.buttonX)
        assertEquals(0, s.buttonY)
        assertEquals(ButtonOverlay.SIZE.first, s.buttonSize)
        assertEquals(ButtonOverlay.SIZE.last, REauSettings(buttonSize = 500).sanitized().buttonSize)
    }

    @Test
    fun `changing the text while running is taken at once and alone`() {
        val before = REauSettings()
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
        for (changed in listOf(before.copy(buttonOverlay = true), before.copy(buttonX = 10), before.copy(buttonY = 10), before.copy(buttonSize = 30))) {
            assertEquals(setOf(LiveTuning.Step.BUTTON_OVERLAY), LiveTuning.steps(before, changed))
        }
    }

    @Test
    fun `the switch is said in the log of the settings in use and the place is not`() {
        assertTrue("buttonOverlay" in SettingsInUse.LOGGED)
        assertTrue("buttonX" in SettingsInUse.NOT_LOGGED && "buttonY" in SettingsInUse.NOT_LOGGED && "buttonSize" in SettingsInUse.NOT_LOGGED)
    }
}
