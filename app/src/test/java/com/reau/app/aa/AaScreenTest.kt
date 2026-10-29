package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Android Auto
 * screen is shown, from the display that has the focused window. The menu is GhAppLauncherService's display; any other
 * of Android Auto's displays is an app (Waze, Google Maps, Spotify, the phone), all with the same fit.
 */
class AaScreenTest {
    private val launcher = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.system.applauncher.GhAppLauncherService}"
    private val waze = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.apphost.TemplateNavigationService}"
    private val maps = "GhostActivityDisplay:ComponentInfo{com.google.android.apps.maps/com.google.android.apps.gmm.car.GmmCarProjectionService}"

    private fun d(id: Int, name: String, focused: Boolean) = AaScreenRule.Display(id, name, focused)

    @Test
    fun `the focused launcher display is the menu`() {
        // what the phone showed with the menu up: display 8 focused, Waze's display 3 not
        assertEquals(AaScreen.MENU, AaScreenRule.of(listOf(d(3, waze, false), d(8, launcher, true), d(0, "Tela integrada", true))))
    }

    private val spotify = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.apphost.TemplateService}"

    @Test
    fun `Waze and Google Maps are the navigation screen, the other apps another, by the display's name`() {
        assertEquals(AaScreen.NAV, AaScreenRule.of(listOf(d(3, waze, true), d(8, launcher, false))))
        assertEquals(AaScreen.NAV, AaScreenRule.of(listOf(d(16, maps, true), d(8, launcher, false))))
        assertEquals(AaScreen.APPS, AaScreenRule.of(listOf(d(5, spotify, true), d(8, launcher, false))))
    }

    @Test
    fun `the phone's own screen and the side bar say nothing`() {
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(listOf(d(0, "Tela integrada", true), d(4, "GhFacetBar", true))))
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(emptyList()))
    }

    @Test
    fun `the display's number never matters, only its name`() {
        assertEquals(AaScreen.MENU, AaScreenRule.of(listOf(d(42, launcher, true))))
    }

    @Test
    fun `a change is taken after it holds, and said once`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        assertNull("the first reading waits too", watch.update(listOf(d(8, launcher, true)), nowMs = 1_000))
        assertEquals("and asks to be read again", true, watch.waiting)
        assertEquals(AaScreen.MENU, watch.update(listOf(d(8, launcher, true)), nowMs = 1_300))
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertEquals(false, watch.waiting)
        assertNull("the same screen again changes nothing", watch.update(listOf(d(8, launcher, true)), nowMs = 2_000))
        assertEquals(1, said.size)
        assertEquals(true, said.single().contains("menu") && said.single().contains("GhAppLauncherService") && said.single().contains("8"))
    }

    /** 21/10/2026: Home to the menu took 1.0 s, 0.4 s of it the hold; right after an input the menu holds a quarter of that. */
    @Test
    fun `the menu coming up right after an input holds only briefly`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        watch.update(listOf(d(3, waze, true), d(8, launcher, false)), nowMs = 0)
        watch.inputSent(nowMs = 10_000)
        assertNull(watch.update(listOf(d(3, waze, false), d(8, launcher, true)), nowMs = 10_500))
        assertEquals("a quick read is asked for", 100L, watch.holdNowMs(10_500))
        assertEquals(AaScreen.MENU, watch.update(listOf(d(8, launcher, true)), nowMs = 10_600))
    }

    @Test
    fun `the menu with no input before it, or one long before, holds the whole time`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        assertNull(watch.update(listOf(d(8, launcher, true)), nowMs = 1_000))
        assertEquals(400L, watch.holdNowMs(1_000))
        assertNull("100 ms is not enough with no input", watch.update(listOf(d(8, launcher, true)), nowMs = 1_100))
        val late = AaScreenWatch(holdMs = 400) {}
        late.inputSent(nowMs = 0)
        assertNull(late.update(listOf(d(8, launcher, true)), nowMs = 2_500))
        assertEquals("the input was 2.5 s ago", 400L, late.holdNowMs(2_500))
    }

    @Test
    fun `an app taking the focus right after an input still holds the whole time`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        watch.update(listOf(d(8, launcher, true)), nowMs = 0)
        watch.update(listOf(d(8, launcher, true)), nowMs = 500)
        watch.inputSent(nowMs = 10_000)
        assertNull(watch.update(listOf(d(3, waze, true), d(8, launcher, false)), nowMs = 10_050))
        assertEquals(400L, watch.holdNowMs(10_050))
        assertNull(watch.update(listOf(d(3, waze, true), d(8, launcher, false)), nowMs = 10_200))
    }

    // ── what a select in the menu picked, when no display says (Google Maps, and Waze at times) ──────────────────

    private val menuOpen = listOf(d(2, spotify, false), d(5, maps, false), d(3, launcher, true))

    @Test
    fun `after a select with no display taking the focus a navigation app is guessed`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(menuOpen, 0)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        // the launcher stays focused, nothing new: before the guess's time it is still the apps'
        watch.update(menuOpen, 10_250)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaScreen.NAV, watch.update(menuOpen, 10_800))
        assertTrue(said.last(), "a guess" in said.last())
        // and it is said once
        assertNull(watch.update(menuOpen, 11_050))
    }

    @Test
    fun `after a select the displays focused together at the select say nothing, and the guess is the navigation (22 10 2026)`() {
        // Waze's display and a template display (a call's card) focused together all along, the menu opened by the Home key
        val template = spotify
        val together = listOf(d(443, waze, true), d(450, launcher, false), d(454, template, true))
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(together, 0)
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent(nowMs = 10_000)
        watch.update(together, 10_100)
        assertEquals("the reading of the same displays is no answer", AaScreen.APPS, watch.screen.value)
        watch.update(together, 10_300)
        assertEquals(AaScreen.NAV, watch.update(together, 10_800))
        assertTrue(said.last(), "a guess" in said.last())
    }

    // ── a session that starts with an app already in front (23/10/2026): the only display of Android Auto, not focused ──

    @Test
    fun `a single display of Android Auto, not focused, is the screen (the session started with Google Maps in front)`() {
        assertEquals(AaScreen.NAV, AaScreenRule.of(listOf(d(693, maps, false))))
        assertEquals(AaScreen.NAV, AaScreenRule.of(listOf(d(693, waze, false))))
        assertEquals(AaScreen.APPS, AaScreenRule.of(listOf(d(5, spotify, false))))
        assertEquals(AaScreen.MENU, AaScreenRule.of(listOf(d(8, launcher, false))))
    }

    @Test
    fun `the phone's own screen and the trampoline beside the single display do not count as displays`() {
        val trampoline = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
            "com.google.android.projection.gearhead.system.ProjectionTrampolineFallbackService}"
        assertEquals(AaScreen.NAV, AaScreenRule.of(listOf(d(693, maps, false), d(0, "Tela integrada", true), d(7, trampoline, false))))
    }

    @Test
    fun `several displays and none focused still say nothing`() {
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(listOf(d(3, waze, false), d(8, launcher, false), d(5, spotify, false))))
    }

    @Test
    fun `the watch takes the single display's screen at the first reading`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        assertEquals(AaScreen.NAV, watch.update(listOf(d(693, maps, false)), 0))
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertTrue(said.toString(), said.any { "nav" in it })
    }

    // ── what the logs of 22/10/2026 showed: the active window is no sign of what is in front, the trampoline's focus is no screen ──

    private fun d(id: Int, name: String, focused: Boolean, active: Boolean) = AaScreenRule.Display(id, name, focused, active)

    private val trampoline = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.system.ProjectionTrampolineFallbackService}"

    @Test
    fun `the active window decides nothing, it stayed on an app's template display for minutes`() {
        val displays = listOf(d(443, waze, focused = true, active = false), d(454, spotify, focused = true, active = true), d(450, launcher, false, false))
        assertEquals(AaScreenRule.of(displays.map { it.copy(active = false) }), AaScreenRule.of(displays))
        val wazeActive = listOf(d(443, waze, focused = true, active = true), d(454, spotify, focused = true, active = false))
        assertEquals("the newest focused display, as before", AaScreen.APPS, AaScreenRule.of(wazeActive))
    }

    @Test
    fun `the trampoline display's focus is no screen and no change of the focused displays`() {
        val displays = listOf(d(443, waze, true), d(448, trampoline, true))
        assertEquals(AaScreen.NAV, AaScreenRule.of(displays))
        assertEquals(setOf(443), AaScreenRule.focusedIds(displays))
        assertEquals(AaScreen.UNKNOWN, AaScreenRule.of(listOf(d(448, trampoline, true))))
    }

    @Test
    fun `a select picks Waze with a template display and the trampoline's flicker, and the guess is the navigation (19 17 2026 log)`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        // Spotify's template display kept the focus for minutes; the trampoline's focus came and went with every change
        val quiet = listOf(d(466, waze, false), d(471, trampoline, false), d(473, launcher, false), d(477, spotify, true))
        val flicker = listOf(d(466, waze, false), d(471, trampoline, true), d(473, launcher, false), d(477, spotify, true))
        watch.update(quiet, 0)
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        watch.update(flicker, 10_100)
        watch.update(quiet, 10_300)
        assertEquals("nothing said what was picked yet", AaScreen.APPS, watch.screen.value)
        assertEquals(AaScreen.NAV, watch.update(flicker, 10_800))
        assertTrue(said.last(), "a guess" in said.last())
    }

    // ── what the picture says where the displays do not ──────────────────────────────────────────────────────────

    private val quietTemplate = listOf(d(466, waze, false), d(471, trampoline, false), d(473, launcher, false), d(477, spotify, true))

    /** A select in the menu with nothing new on the displays, and the guess made: the screen is the navigation's, unconfirmed. */
    private fun guessed(said: MutableList<String> = mutableListOf()): AaScreenWatch {
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(quietTemplate, 0)
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        assertEquals(AaScreen.NAV, watch.update(quietTemplate, 10_800))
        return watch
    }

    @Test
    fun `dark pictures after a guess say it was not a navigation app`() {
        val said = mutableListOf<String>()
        val watch = guessed(said)
        assertNull("too soon after the select", watch.look(PictureLook.Kind.APPS, 10_700))
        assertNull(watch.look(PictureLook.Kind.APPS, 10_900))
        assertNull(watch.look(PictureLook.Kind.APPS, 11_400))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 11_900))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.last(), "by the picture" in said.last() && "not a map" in said.last())
    }

    @Test
    fun `a map picture confirms the guess once and a dark one in between starts over`() {
        val said = mutableListOf<String>()
        val watch = guessed(said)
        watch.look(PictureLook.Kind.MAP, 10_900)
        watch.look(PictureLook.Kind.APPS, 11_400)
        watch.look(PictureLook.Kind.APPS, 11_900)
        assertEquals("two dark ones are not three", AaScreen.NAV, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 12_400)
        assertNull(watch.look(PictureLook.Kind.MAP, 12_900))
        assertEquals(1, said.count { "confirmed by the picture" in it })
        watch.look(PictureLook.Kind.MAP, 13_400)
        assertEquals("said once", 1, said.count { "confirmed by the picture" in it })
    }

    @Test
    fun `a map picture takes an apps' screen the guess did not reach, when no display said what it was`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(quietTemplate, 0)
        watch.homeSent()
        watch.selectSent(nowMs = 10_000) // the screen is the apps' until the guess, 800 ms later
        assertNull(watch.look(PictureLook.Kind.MAP, 10_850))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 11_350))
        assertTrue(said.last(), "by the picture" in said.last() && "map" in said.last())
    }

    @Test
    fun `a display that said what the screen is is not changed by the picture`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(quietTemplate, 0)
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        // Spotify's display opens: the displays say what it is
        watch.update(listOf(d(466, waze, false), d(477, spotify, true), d(481, spotify, true)), 10_300)
        assertEquals(AaScreen.APPS, watch.screen.value)
        for (t in listOf(11_000L, 11_500L, 12_000L)) assertNull(watch.look(PictureLook.Kind.MAP, t))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `the menu and the Home key are not changed by the picture`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(quietTemplate, 0)
        watch.homeSent()
        for (t in listOf(1_000L, 1_500L, 2_000L)) assertNull(watch.look(PictureLook.Kind.MAP, t))
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `a display letting go of the focus after the select still says what it is`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        val together = listOf(d(443, waze, true), d(450, launcher, false), d(454, spotify, true))
        watch.update(together, 0)
        watch.homeSent()
        watch.selectSent(nowMs = 10_000)
        // the template display lets go: the focused set changed, and what is left is Waze
        val wazeOnly = listOf(d(443, waze, true), d(450, launcher, false), d(454, spotify, false))
        watch.update(wazeOnly, 10_100)
        assertEquals(AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `a display taking the focus anew after the select says what it is, and no guess`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(menuOpen, 0)
        watch.selectSent(nowMs = 10_000)
        val toSpotify = listOf(d(2, spotify, true), d(5, maps, false), d(3, launcher, true))
        watch.update(toSpotify, 10_250)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertNull("no guess over what the displays said", watch.update(toSpotify, 10_900))
        assertEquals(AaScreen.APPS, watch.screen.value)
        // and not even when the focus then goes elsewhere (nothing focused at the guess's time)
        val none = listOf(d(2, spotify, false), d(5, maps, false), d(3, launcher, false))
        assertNull(watch.update(none, 11_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `a navigation display taking the focus later after the guess is the same screen, the menu opening again is the menu`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(menuOpen, 0)
        watch.selectSent(nowMs = 10_000)
        watch.update(menuOpen, 10_800)
        assertEquals(AaScreen.NAV, watch.screen.value)
        // a reading of the launcher kept focused changes nothing
        assertNull(watch.update(menuOpen, 12_000))
        // the Home key opens the menu again, the select leaves it, and the guess starts over
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent(nowMs = 20_000)
        watch.update(menuOpen, 20_300)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaScreen.NAV, watch.update(menuOpen, 20_800))
    }

    /** The 26/10/2026 sequence: Waze picked in the menu, nothing focused for a reading, then the launcher's display focused again while the map is in front. */
    private fun wazePickedWithAMapInFront(said: MutableList<String> = mutableListOf()): AaScreenWatch {
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(listOf(d(1384, waze, false), d(1391, launcher, true)), 0)
        watch.selectSent(nowMs = 10_000)
        watch.update(listOf(d(1384, waze, false), d(1391, launcher, false)), 10_350) // nothing focused for a moment
        assertNull(watch.look(PictureLook.Kind.MAP, 10_450))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 10_700))
        return watch
    }

    @Test
    fun `the launcher's display focused again while the picture is a map does not take the navigation for the menu (26 10 2026)`() {
        val said = mutableListOf<String>()
        val watch = wazePickedWithAMapInFront(said)
        val launcherAgain = listOf(d(1384, waze, false), d(1391, launcher, true))
        assertNull(watch.update(launcherAgain, 10_780, reread = true))
        assertEquals(AaScreen.NAV, watch.screen.value)
        // and at every read after it, as long as the picture is a map
        for (t in listOf(11_800L, 12_800L, 13_800L)) {
            watch.look(PictureLook.Kind.MAP, t - 100)
            assertNull(watch.update(launcherAgain, t, reread = true))
            assertEquals(AaScreen.NAV, watch.screen.value)
        }
        assertEquals("said once", 1, said.count { it.contains("still navigation") })
    }

    @Test
    fun `a look that is unsure in between does not end it, a look of the menu or an app does, and then the launcher is the menu`() {
        val watch = wazePickedWithAMapInFront()
        val launcherAgain = listOf(d(1384, waze, false), d(1391, launcher, true))
        watch.look(PictureLook.Kind.UNSURE, 10_900)
        assertNull(watch.update(launcherAgain, 11_000, reread = true))
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.look(PictureLook.Kind.APPS, 11_100)
        assertEquals(AaScreen.MENU, watch.update(launcherAgain, 11_200, reread = true))
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `a map not seen for a while no longer holds the navigation against the launcher`() {
        val watch = wazePickedWithAMapInFront()
        val launcherAgain = listOf(d(1384, waze, false), d(1391, launcher, true))
        assertEquals(AaScreen.MENU, watch.update(launcherAgain, 10_700 + 3_500, reread = true))
    }

    @Test
    fun `the Home key still opens the menu at once with a map in front`() {
        val watch = wazePickedWithAMapInFront()
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `the guess of a navigation app is held by the picture too against the launcher focused anew`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(menuOpen, 0)
        watch.selectSent(nowMs = 10_000)
        watch.update(menuOpen, 10_800)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 10_900)
        val none = listOf(d(2, spotify, false), d(5, maps, false), d(3, launcher, false))
        watch.update(none, 11_000)
        assertNull(watch.update(menuOpen, 11_100, reread = true))
        assertEquals(AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `a navigation taken from a display is not held by the picture against the launcher taking the focus`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(1384, waze, true), d(1391, launcher, false)), 0)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 100)
        assertEquals(AaScreen.MENU, watch.update(listOf(d(1384, waze, false), d(1391, launcher, true)), 150))
    }

    @Test
    fun `no select time, no guess (a select with no clock), and a reset forgets it`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(menuOpen, 0)
        watch.selectSent()
        assertNull(watch.update(menuOpen, 99_000))
        val again = AaScreenWatch(holdMs = 0) {}
        again.update(menuOpen, 0)
        again.selectSent(nowMs = 10_000)
        again.reset()
        again.update(menuOpen, 10_800)
        assertEquals("a session over: nothing guessed", AaScreen.MENU, again.screen.value)
    }

    // ── a display's focus that comes late (4 of 20 guesses of 22/10/2026 were corrected 143 to 260 ms later), and the side bar's shortcuts ──

    private val telecom = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
        "com.google.android.projection.gearhead.telecom.TelecomService}"

    @Test
    fun `Spotify's display taking the focus 700 ms after the select is no flash of the navigation`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(menuOpen, 0)
        watch.selectSent(nowMs = 10_000)
        watch.update(menuOpen, 10_500)
        assertEquals("before the guess's time it is the apps'", AaScreen.APPS, watch.screen.value)
        val toSpotify = listOf(d(2, spotify, true), d(5, maps, false), d(3, launcher, true))
        watch.update(toSpotify, 10_700)
        watch.update(toSpotify, 11_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.toString(), said.none { "a guess" in it })
    }

    /** Spotify's template display focused, the apps' screen (no displays of the menu or the navigation in front). */
    private val inSpotify = listOf(d(2, spotify, true), d(5, maps, false), d(3, launcher, false))

    private fun inSpotifyWatch(said: MutableList<String> = mutableListOf(), lastLook: PictureLook.Kind? = PictureLook.Kind.MAP): AaScreenWatch {
        val watch = AaScreenWatch(holdMs = 0) { said += it }
        watch.update(inSpotify, 0)
        assertEquals(AaScreen.APPS, watch.screen.value)
        lastLook?.let { watch.look(it, 9_500) }
        return watch
    }

    @Test
    fun `a tap on the media shortcut with Spotify in front (a lit picture) and no display taking the focus guesses the map (22 10 2026 log)`() {
        val said = mutableListOf<String>()
        val watch = inSpotifyWatch(said)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertTrue(said.last(), "side bar" in said.last())
        // Android Auto went back to the map, the focused display is still Spotify's
        assertNull(watch.update(inSpotify, 10_300))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertEquals(AaScreen.NAV, watch.update(inSpotify, 10_800))
        assertTrue(said.last(), "a guess" in said.last())
    }

    @Test
    fun `a tap on the media shortcut with a dark picture before it opens Spotify, no guess (Settings shares Spotify's display, 23 10 2026 run)`() {
        val said = mutableListOf<String>()
        val watch = inSpotifyWatch(said, lastLook = PictureLook.Kind.APPS)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertNull(watch.update(inSpotify, 10_800))
        assertNull(watch.update(inSpotify, 12_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.toString(), said.none { "side bar" in it || "a guess" in it })
    }

    @Test
    fun `a tap on the media shortcut with no look of the picture yet changes nothing`() {
        val watch = inSpotifyWatch(lastLook = null)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        assertNull(watch.update(inSpotify, 10_800))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `a tap on the navigation shortcut guesses the map whatever the picture was`() {
        for (look in listOf(PictureLook.Kind.APPS, PictureLook.Kind.UNSURE, null)) {
            val watch = inSpotifyWatch(lastLook = look)
            watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.NAVIGATION)
            assertEquals("$look", AaScreen.NAV, watch.update(inSpotify, 10_800))
        }
    }

    @Test
    fun `a tap on a shortcut and a display taking the focus anew is that display's screen, and no guess`() {
        val said = mutableListOf<String>()
        val watch = inSpotifyWatch(said)
        watch.selectSent(nowMs = 10_000, rail = AaRail.Slot.MEDIA)
        val toCall = listOf(d(2, spotify, false), d(5, maps, false), d(3, launcher, false), d(7, telecom, true))
        watch.update(toCall, 10_400)
        assertNull("no guess over what the displays said", watch.update(toCall, 11_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.toString(), said.none { "a guess" in it })
    }

    @Test
    fun `a tap on a shortcut changes nothing in the navigation or in the menu`() {
        val said = mutableListOf<String>()
        for (slot in AaRail.Slot.entries) {
            val navShown = AaScreenWatch(holdMs = 0) { said += it }
            navShown.update(listOf(d(3, waze, true), d(8, launcher, false)), 0)
            navShown.look(PictureLook.Kind.MAP, 9_500)
            navShown.selectSent(nowMs = 10_000, rail = slot)
            assertNull(navShown.update(listOf(d(3, waze, true), d(8, launcher, false)), 11_000))
            assertEquals(AaScreen.NAV, navShown.screen.value)
            val menuShown = AaScreenWatch(holdMs = 0) { said += it }
            menuShown.update(menuOpen, 0)
            menuShown.look(PictureLook.Kind.MAP, 9_500)
            menuShown.selectSent(nowMs = 10_000, rail = slot)
            assertNull(menuShown.update(menuOpen, 11_000))
            assertEquals(AaScreen.MENU, menuShown.screen.value)
        }
        assertTrue(said.toString(), said.none { "side bar" in it })
    }

    @Test
    fun `a tap on a shortcut with no clock guesses nothing`() {
        val watch = inSpotifyWatch()
        watch.selectSent(rail = AaRail.Slot.NAVIGATION)
        assertNull(watch.update(inSpotify, 99_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `a session over forgets the last look`() {
        val watch = inSpotifyWatch()
        watch.reset()
        watch.update(inSpotify, 20_000)
        watch.selectSent(nowMs = 30_000, rail = AaRail.Slot.MEDIA)
        assertNull(watch.update(inSpotify, 31_000))
    }

    // ── the menu opened by the Home key, which the displays do not always say (5 of 11 openings on 21/10/2026) ──

    @Test
    fun `the Home key takes the menu at once and the same focused displays do not take it back`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 400) { said += it }
        val inApp = listOf(d(2, spotify, true), d(3, launcher, false), d(4, "ProjectionTrampolineFallbackService", true))
        watch.update(inApp, 0)
        watch.update(inApp, 500)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertTrue(said.last(), "the Home key" in said.last())
        // the app's display keeps its focus, as it did: still the menu, however long
        for (t in listOf(1_000L, 2_000L, 20_000L)) watch.update(inApp, t)
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `the select leaves the menu the Home key opened`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        val inApp = listOf(d(2, spotify, true), d(3, launcher, false))
        watch.update(inApp, 0)
        watch.update(inApp, 500)
        watch.homeSent()
        watch.selectSent()
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.update(inApp, 1_000)
        watch.update(inApp, 1_500)
        assertEquals("the displays that were focused all along say nothing new", AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `after the select the displays say which app it was, as they always do`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        val both = listOf(d(2, spotify, true), d(5, maps, true), d(3, launcher, false))
        watch.update(both, 0)
        watch.update(both, 500)
        watch.homeSent()
        watch.selectSent()
        // Maps was focused all along; Spotify lets go of the focus: navigation is what is shown, after the hold
        val mapsOnly = listOf(d(2, spotify, false), d(5, maps, true), d(3, launcher, false))
        watch.update(mapsOnly, 2_000)
        assertEquals(AaScreen.NAV, watch.update(mapsOnly, 2_400))
    }

    @Test
    fun `a display focusing anew ends it, and the launcher alone makes the displays the truth again`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        val inApp = listOf(d(2, spotify, true), d(3, launcher, false))
        watch.update(inApp, 0)
        watch.update(inApp, 500)
        watch.homeSent()
        // Waze takes the focus anew: it is what is in front now, after the hold
        val toWaze = listOf(d(2, spotify, false), d(3, launcher, false), d(4, waze, true))
        watch.update(toWaze, 1_000)
        assertEquals(AaScreen.NAV, watch.update(toWaze, 1_400))
        // The launcher alone: the displays decide again, so an app taking over later is followed
        val again = AaScreenWatch(holdMs = 400) {}
        again.update(inApp, 0)
        again.update(inApp, 500)
        again.homeSent()
        again.update(listOf(d(3, launcher, true)), 1_000)
        again.update(listOf(d(3, launcher, true)), 1_400)
        val elsewhere = listOf(d(3, launcher, false), d(2, spotify, true))
        again.update(elsewhere, 3_000)
        assertEquals(AaScreen.APPS, again.update(elsewhere, 3_400))
    }

    @Test
    fun `the Home key in the menu, or after a reset, changes nothing more`() {
        val watch = AaScreenWatch(holdMs = 400) {}
        watch.update(listOf(d(3, launcher, true)), 0)
        watch.update(listOf(d(3, launcher, true)), 500)
        watch.homeSent()
        // the menu was already shown: the displays still decide
        val toApp = listOf(d(3, launcher, false), d(2, spotify, true))
        watch.update(toApp, 1_000)
        assertEquals(AaScreen.APPS, watch.update(toApp, 1_400))
        watch.homeSent()
        watch.reset()
        watch.update(toApp, 2_000)
        assertEquals("a session over: the Home key is forgotten", AaScreen.APPS, watch.update(toApp, 2_400))
    }

    @Test
    fun `the process's watcher takes a new screen at once, in the debug build and in the release one`() {
        assertEquals(0L, AaScreens.HOLD_IN_USE_MS)
        assertEquals(400L, AaScreens.HOLD_MS)
        // A release must not differ from the debug build the rider tests with: the value reads no build type.
        val line = java.io.File("src/main/java/com/reau/app/aa/AaScreen.kt").takeIf { it.isFile }?.readLines()
            ?: java.io.File("app/src/main/java/com/reau/app/aa/AaScreen.kt").readLines()
        assertTrue(line.single { "HOLD_IN_USE_MS:" in it }.let { "BuildConfig" !in it })
    }

    /** The shown display's name follows too, from one app to another as well, after the same hold. */
    @Test
    fun `the shown display's name is taken after it holds, from one app to another too`() {
        val watch = AaScreenWatch(holdMs = 300) {}
        watch.update(listOf(d(3, waze, true)), nowMs = 1_000)
        assertNull(watch.shownName.value)
        watch.update(listOf(d(3, waze, true)), nowMs = 1_300)
        assertEquals("TemplateNavigationService", watch.shownName.value)
        watch.update(listOf(d(3, waze, false), d(16, maps, true)), nowMs = 2_000)
        assertEquals("an app to another: the name waits", "TemplateNavigationService", watch.shownName.value)
        assertEquals("and asks to be read again", true, watch.waiting)
        watch.update(listOf(d(3, waze, false), d(16, maps, true)), nowMs = 2_300)
        assertEquals("GmmCarProjectionService", watch.shownName.value)
        assertEquals(false, watch.waiting)
        watch.update(listOf(d(8, launcher, true)), nowMs = 3_000)
        watch.update(listOf(d(16, maps, true)), nowMs = 3_100)
        watch.update(listOf(d(16, maps, true)), nowMs = 3_500)
        assertEquals("a flicker shorter than the hold", "GmmCarProjectionService", watch.shownName.value)
        watch.update(listOf(d(0, "Tela integrada", true)), nowMs = 4_000)
        watch.update(listOf(d(0, "Tela integrada", true)), nowMs = 4_400)
        assertEquals("none of Android Auto's focused: the name stays", "GmmCarProjectionService", watch.shownName.value)
    }

    @Test
    fun `a flicker shorter than the hold changes nothing`() {
        val watch = AaScreenWatch(holdMs = 300) {}
        watch.update(listOf(d(8, launcher, true)), 0)
        watch.update(listOf(d(8, launcher, true)), 300)
        assertNull(watch.update(listOf(d(3, waze, true)), 400))
        assertNull("back to the menu before the hold", watch.update(listOf(d(8, launcher, true)), 500))
        assertNull(watch.update(listOf(d(8, launcher, true)), 900))
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    /**
     * Since Google Maps
     * is heard, its display may stay focused while another takes the focus too. The display that has just taken it wins;
     * with no new one, the one shown before, while it keeps the focus.
     */
    @Test
    fun `with two displays focused, the one that has just taken the focus is shown`() {
        val template = "GhostActivityDisplay:ComponentInfo{com.google.android.projection.gearhead/" +
            "com.google.android.projection.gearhead.apphost.TemplateService}"
        val watch = AaScreenWatch(holdMs = 400) {}
        fun twice(at: Long, vararg displays: AaScreenRule.Display) {
            watch.update(displays.toList(), at)
            watch.update(displays.toList(), at + 450) // read again after the hold
        }
        twice(0, d(170, maps, true))
        assertEquals(AaScreen.NAV, watch.screen.value)
        twice(2_000, d(170, maps, true), d(173, launcher, true))
        assertEquals("the menu, Maps still focused", AaScreen.MENU, watch.screen.value)
        twice(4_000, d(170, maps, true), d(173, launcher, false), d(177, template, true))
        assertEquals("an app", AaScreen.APPS, watch.screen.value)
        twice(15_000, d(170, maps, true), d(173, launcher, true), d(177, template, false))
        assertEquals("the menu again", AaScreen.MENU, watch.screen.value)
        twice(21_000, d(170, maps, true), d(173, launcher, false), d(177, template, false))
        assertEquals("Maps, the only one focused", AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `the display that took the focus stays shown while the other keeps its focus too`() {
        // the menu (a newer display) still focused when Maps takes the focus: Maps holds, read after read
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(170, maps, false), d(173, launcher, true)), 0)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.update(listOf(d(170, maps, true), d(173, launcher, true)), 100)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.update(listOf(d(170, maps, true), d(173, launcher, true)), 200)
        assertEquals("the same reading again keeps Maps", AaScreen.NAV, watch.screen.value)
    }

    @Test
    fun `unknown keeps the last screen`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(3, spotify, true)), 0)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertNull(watch.update(listOf(d(0, "Tela integrada", true)), 10))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    /**
     * A synthetic sequence of readings: the joystick's select opens the menu from Waze's map, a second select picks Waze,
     * and Android Auto leaves the launcher's display focused, reading after reading. Without the select rule the menu's
     * fit stays on the map until Waze's display takes the focus again.
     */
    @Test
    fun `a select in the menu is the apps at once, and the launcher kept focused is no menu`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 400) { said += it }
        fun read(at: Long, wazeFocused: Boolean?, launcherFocused: Boolean) =
            watch.update(listOfNotNull(wazeFocused?.let { d(21, spotify, it) }, d(22, launcher, launcherFocused)), at)
        watch.update(listOf(d(21, spotify, true)), -60_000)
        watch.update(listOf(d(21, spotify, true)), -59_500)
        assertEquals(AaScreen.APPS, watch.screen.value)
        read(800, wazeFocused = true, launcherFocused = false)
        read(850, wazeFocused = true, launcherFocused = true)
        read(1_000, wazeFocused = false, launcherFocused = true)
        read(1_600, wazeFocused = null, launcherFocused = true)
        assertEquals("the menu", AaScreen.MENU, watch.screen.value)

        watch.selectSent() // Waze picked in the menu
        assertEquals("the apps at once", AaScreen.APPS, watch.screen.value)
        assertEquals("aa-screen: apps (a select in the menu)", said.last())
        for ((at, waze) in listOf(12_850L to false, 13_050L to null, 13_150L to false, 13_250L to null, 13_650L to false, 14_250L to null,
            70_000L to false, 70_200L to null, 130_000L to false, 130_200L to null, 131_000L to null)) {
            read(at, wazeFocused = waze, launcherFocused = true)
            assertEquals("the launcher still focused at +$at ms is not the menu", AaScreen.APPS, watch.screen.value)
        }
        watch.selectSent() // a select on the map changes nothing
        assertEquals(AaScreen.APPS, watch.screen.value)
        read(260_000, wazeFocused = true, launcherFocused = true) // Waze's display takes the focus too
        assertEquals(AaScreen.APPS, watch.screen.value)

        // the menu opened again: the launcher's display takes the focus anew, and is the menu after the hold
        read(300_000, wazeFocused = true, launcherFocused = false)
        read(300_100, wazeFocused = false, launcherFocused = true)
        read(300_600, wazeFocused = false, launcherFocused = true)
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `after a select the menu taking the focus anew is the menu again`() {
        val watch = AaScreenWatch(holdMs = 0) {}
        watch.update(listOf(d(3, spotify, false), d(8, launcher, true)), 0)
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.selectSent()
        watch.update(listOf(d(3, spotify, false), d(8, launcher, true)), 100)
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.update(listOf(d(3, spotify, false)), 200) // the launcher's window gone for a reading
        watch.update(listOf(d(3, spotify, false), d(8, launcher, true)), 300) // and focused again: opened anew
        assertEquals(AaScreen.MENU, watch.screen.value)
    }

    @Test
    fun `a reset forgets the screen, what waits and the select, so the next session starts from nothing`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.update(listOf(d(8, launcher, true)), nowMs = 1_000)
        assertEquals(AaScreen.MENU, watch.update(listOf(d(8, launcher, true)), nowMs = 1_300))
        watch.update(listOf(d(3, waze, true)), nowMs = 1_400) // a change waiting
        assertEquals(true, watch.waiting)

        watch.reset()

        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        assertNull(watch.shownName.value)
        assertEquals("nothing waits any more", false, watch.waiting)
        // and it works again as new: the first reading waits its hold
        assertNull(watch.update(listOf(d(3, waze, true)), nowMs = 5_000))
        assertEquals(AaScreen.NAV, watch.update(listOf(d(3, waze, true)), nowMs = 5_300))
    }

    @Test
    fun `the end of a projection resets the process's watcher`() {
        AaScreens.watch.update(listOf(d(8, launcher, true)), nowMs = 1_000)
        AaScreens.watch.update(listOf(d(8, launcher, true)), nowMs = 1_500)
        assertEquals(AaScreen.MENU, AaScreens.watch.screen.value)
        AaScreens.projection(false)
        assertEquals("the next session does not start in the menu", AaScreen.UNKNOWN, AaScreens.watch.screen.value)
    }

    // 24/10/2026: Android Auto 11.8 on Android 11 (the test phone "harpia"): its virtual displays are private, the accessibility service lists none of
    // their windows, and Google Maps in front kept the apps' crop.

    @Test
    fun `with no display of Android Auto's read, two looks of a map after 3 seconds make the navigation screen`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        watch.update(listOf(d(0, "Tela integrada", true)), nowMs = 500)
        assertNull("the first looks are inside the 3 seconds", watch.look(PictureLook.Kind.MAP, 1_000))
        assertNull(watch.look(PictureLook.Kind.MAP, 2_500))
        assertNull("the picture starts counting at 3 s", watch.look(PictureLook.Kind.MAP, 4_000))
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 4_500))
        assertEquals(AaScreen.NAV, watch.screen.value)
        assertTrue(said.any { "no display of Android Auto's read" in it })
        assertTrue(said.any { "by the picture: a map" in it })
    }

    @Test
    fun `with no display read, three looks of an app make the apps from unknown, two of a map the navigation, three of an app the apps again`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        watch.look(PictureLook.Kind.APPS, 1_000)
        assertNull("inside the 3 seconds", watch.look(PictureLook.Kind.APPS, 2_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 4_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 4_500))
        assertEquals("an app seen three times from unknown", AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 5_000))
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertTrue(said.any { "apps (by the picture: an app, from unknown)" in it })
        watch.look(PictureLook.Kind.MAP, 6_000)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 6_500))
        assertNull(watch.look(PictureLook.Kind.APPS, 7_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 7_500))
        assertEquals(AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 8_000))
    }

    @Test
    fun `with displays read, a picture of an app from unknown still says nothing`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.update(listOf(d(8, launcher, false)), nowMs = 500)
        for (t in listOf(1_000L, 4_000L, 4_500L, 5_000L, 5_500L)) assertNull(watch.look(PictureLook.Kind.APPS, t))
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
    }

    @Test
    fun `a display of Android Auto's read ends the picture's rule, the displays decide`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.look(PictureLook.Kind.MAP, 1_000)
        watch.update(listOf(d(8, launcher, false)), nowMs = 2_000)
        for (t in listOf(4_000L, 4_500L, 5_000L)) assertNull("from unknown the picture says nothing now", watch.look(PictureLook.Kind.MAP, t))
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
    }

    @Test
    fun `a display read before the 3 seconds never lets the picture decide, and a reset starts the wait again`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.update(listOf(d(3, waze, false)), nowMs = 500)
        watch.look(PictureLook.Kind.MAP, 1_000)
        assertNull(watch.look(PictureLook.Kind.MAP, 5_000))
        assertNull(watch.look(PictureLook.Kind.MAP, 5_500))

        watch.reset()
        watch.look(PictureLook.Kind.MAP, 10_000)
        assertNull("the 3 seconds count from the new session's first look", watch.look(PictureLook.Kind.MAP, 11_000))
        watch.look(PictureLook.Kind.MAP, 13_500)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 14_000))
    }
    // 24/10/2026: the net says the menu and the apps too. With no display read it listens all the time and may leave the menu; where displays are read it is as before.

    @Test
    fun `with no display read, three looks of the menu make the menu, and three of an app or two of a map leave it`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        watch.look(PictureLook.Kind.MENU, 1_000)
        assertNull(watch.look(PictureLook.Kind.MENU, 4_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 4_500))
        assertEquals(AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 5_000))
        assertEquals(AaScreen.MENU, watch.screen.value)
        assertTrue(said.any { "menu (by the picture: the app menu, from unknown)" in it })
        assertNull(watch.look(PictureLook.Kind.MENU, 5_500))
        assertNull(watch.look(PictureLook.Kind.APPS, 6_000))
        assertNull(watch.look(PictureLook.Kind.APPS, 6_500))
        assertEquals("an app taken from the menu", AaScreen.APPS, watch.look(PictureLook.Kind.APPS, 7_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 7_500))
        assertNull(watch.look(PictureLook.Kind.MENU, 8_000))
        assertEquals("the menu back from an app", AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 8_500))
        assertNull(watch.look(PictureLook.Kind.MAP, 9_000))
        assertEquals("a map takes the menu away in two looks", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 9_500))
    }

    @Test
    fun `with no display read, the menu and an app in turn never count together, and a look that is not sure starts the count over`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.look(PictureLook.Kind.MENU, 1_000)
        for ((i, kind) in listOf(PictureLook.Kind.MENU, PictureLook.Kind.APPS, PictureLook.Kind.MENU, PictureLook.Kind.APPS, PictureLook.Kind.MENU).withIndex()) {
            assertNull("alternating at ${4_000 + i * 500}", watch.look(kind, 4_000L + i * 500))
        }
        assertEquals(AaScreen.UNKNOWN, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MENU, 7_000))
        assertNull(watch.look(PictureLook.Kind.UNSURE, 7_500))
        assertNull(watch.look(PictureLook.Kind.MENU, 8_000))
        assertNull(watch.look(PictureLook.Kind.MENU, 8_500))
        assertEquals(AaScreen.MENU, watch.look(PictureLook.Kind.MENU, 9_000))
    }

    @Test
    fun `where displays are read, the menu and the apps count together as not a map, and the menu is never taken from the picture`() {
        val watch = AaScreenWatch(holdMs = 0) { }
        watch.update(listOf(d(3, waze, true)), nowMs = 500)
        assertEquals(AaScreen.NAV, watch.screen.value)
        watch.homeSent()
        watch.selectSent(nowMs = 1_000)       // the menu was left by a select: the picture is listening
        assertEquals(AaScreen.APPS, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 2_000)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 2_500))
        watch.look(PictureLook.Kind.MENU, 3_000)
        watch.look(PictureLook.Kind.APPS, 3_500)
        assertEquals("a menu and an app in a row are three looks of not a map", AaScreen.APPS, watch.look(PictureLook.Kind.MENU, 4_000))
        for (t in listOf(5_000L, 5_500L, 6_000L, 6_500L)) assertNull("the menu is the displays' to say", watch.look(PictureLook.Kind.MENU, t))
        assertEquals(AaScreen.APPS, watch.screen.value)
    }

    @Test
    fun `the Home key in blind mode does not silence the picture`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.look(PictureLook.Kind.MAP, 1_000)
        watch.look(PictureLook.Kind.MAP, 4_000)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 4_500))
        watch.homeSent()
        assertEquals(AaScreen.MENU, watch.screen.value)
        watch.look(PictureLook.Kind.MAP, 5_000)
        assertEquals("the map is back (the menu was left by a tap the app does not see)", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 5_500))
    }

    @Test
    fun `after the Home key the map that is still leaving is no new map, and a map after the settle is`() {
        val watch = AaScreenWatch(holdMs = 300) { }
        watch.look(PictureLook.Kind.MAP, 1_000)
        watch.look(PictureLook.Kind.MAP, 4_000)
        assertEquals(AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 4_500))
        watch.homeSent(nowMs = 5_000)
        assertEquals(AaScreen.MENU, watch.screen.value)
        for (t in listOf(5_100L, 5_350L, 5_600L)) assertNull("the old map at $t", watch.look(PictureLook.Kind.MAP, t))
        assertEquals("still the menu", AaScreen.MENU, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MAP, 5_900))
        assertEquals("a map after the settle", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 6_150))
    }

    @Test
    fun `after a select the map of the app picked is taken as soon as it is drawn, without waiting for the settle`() {
        val watch = AaScreenWatch(holdMs = 0) { }
        watch.update(listOf(d(3, waze, true)), nowMs = 500)
        watch.homeSent()
        watch.selectSent(nowMs = 1_000)
        assertEquals(AaScreen.APPS, watch.screen.value)
        assertNull(watch.look(PictureLook.Kind.MAP, 1_200))
        assertEquals("two looks of a map, before the 800 ms of the settle", AaScreen.NAV, watch.look(PictureLook.Kind.MAP, 1_450))
    }
}
