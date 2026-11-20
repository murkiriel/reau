package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.service.LiveTuning
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FPS test card's two settings (plan 32, 20/11/2026): a switch and a pace, both a debug build's: off and 6 by default, the pace brought to the nearest of 4, 6, 8, 10 and 12,
 * forced off by a release, kept when saved, taken at once with the projection running, on its own section of the screen "Tela do Android Auto" inside the debug check, and
 * free to be on together with the two texts over the video.
 */
class FpsTestCardSettingsTest {
    @Test
    fun `it is off at 6 fps by default`() {
        val s = REauSettings()
        assertFalse(s.fpsTestCard)
        assertEquals(6, s.fpsTestCardFps)
        assertEquals(null, s.fpsTestRate)
        assertEquals(listOf(4, 6, 8, 10, 12), REauSettings.FPS_TEST_CARD_RATES)
    }

    @Test
    fun `the pace is brought to the nearest of the list, the lower on a tie`() {
        val brought = listOf(3, 5, 7, 13, 0, -4, 4, 6, 8, 9, 10, 11, 12, 99).map { REauSettings(fpsTestCardFps = it).sanitized(debugBuild = true).fpsTestCardFps }
        assertEquals(listOf(4, 4, 6, 12, 4, 4, 4, 6, 8, 8, 10, 10, 12, 12), brought)
        assertEquals("a release keeps the pace in the list too", 6, REauSettings(fpsTestCardFps = 7).sanitized(debugBuild = false).fpsTestCardFps)
    }

    @Test
    fun `a release forces the card off and a debug build keeps it`() {
        val on = REauSettings(fpsTestCard = true, fpsTestCardFps = 10)
        assertEquals(true to 10, on.sanitized(debugBuild = true).let { it.fpsTestCard to it.fpsTestCardFps })
        assertEquals(false, on.sanitized(debugBuild = false).fpsTestCard)
        // Stored on by a debug build, still forced off when a release reads it.
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(on)
        assertEquals(false, SettingsStore(prefs).load().sanitized(debugBuild = false).fpsTestCard)
    }

    @Test
    fun `the video takes the card only in a debug build, at the pace chosen`() {
        assertEquals(if (com.reau.app.BuildConfig.DEBUG) 8 else null, REauSettings(fpsTestCard = true, fpsTestCardFps = 8).fpsTestRate)
        assertEquals(null, REauSettings(fpsTestCard = false, fpsTestCardFps = 8).fpsTestRate)
        assertTrue("the gate is in the getter", File("src/main/java/com/reau/app/settings/REauSettings.kt").readText().contains("if (fpsTestCard && com.reau.app.BuildConfig.DEBUG)"))
    }

    @Test
    fun `the switch and the pace are kept when saved`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(REauSettings(fpsTestCard = true, fpsTestCardFps = 12))
        val back = SettingsStore(prefs).load()
        assertEquals(true, back.fpsTestCard)
        assertEquals(12, back.fpsTestCardFps)
        assertEquals(false, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().fpsTestCard)
        assertEquals(6, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().fpsTestCardFps)
    }

    @Test
    fun `the three switches over the video can be on together, none turns another off`() {
        val all = REauSettings(statsOverlay = true, buttonOverlay = true, fpsTestCard = true, fpsTestCardFps = 12)
        val kept = all.sanitized(debugBuild = true)
        assertEquals(listOf(true, true, true), listOf(kept.statsOverlay, kept.buttonOverlay, kept.fpsTestCard))
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(prefs).save(all)
        val back = SettingsStore(prefs).load()
        assertEquals(listOf(true, true, true), listOf(back.statsOverlay, back.buttonOverlay, back.fpsTestCard))
        // Turning one on or off through the screen's own copy leaves the other two as they were.
        val withoutCard = kept.copy(fpsTestCard = false)
        assertEquals(listOf(true, true, false), listOf(withoutCard.statsOverlay, withoutCard.buttonOverlay, withoutCard.fpsTestCard))
        val withoutStats = kept.copy(statsOverlay = false)
        assertEquals(listOf(false, true, true), listOf(withoutStats.statsOverlay, withoutStats.buttonOverlay, withoutStats.fpsTestCard))
        // The compositor takes the two texts the same way whether the card is on or not.
        assertEquals(kept.overlay, kept.copy(fpsTestCard = false).overlay)
        assertEquals(kept.buttonLook, kept.copy(fpsTestCard = false).buttonLook)
        // The settings in use say all three.
        val line = SettingsInUse.line(all)
        for (part in listOf("statsOverlay=true", "buttonOverlay=true", "fpsTestCard=true", "fpsTestCardFps=12")) assertTrue("$part in $line", part in line)
    }

    @Test
    fun `the card's own two settings are said in the log of the settings in use`() {
        assertTrue("fpsTestCard" in SettingsInUse.LOGGED && "fpsTestCardFps" in SettingsInUse.LOGGED)
    }

    @Test
    fun `changing the card or its pace while it is on is one step, and the pace while it is off is none`() {
        val before = REauSettings()
        assertEquals(emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy()))
        if (com.reau.app.BuildConfig.DEBUG) {
            assertEquals(setOf(LiveTuning.Step.FPS_TEST), LiveTuning.steps(before, before.copy(fpsTestCard = true)))
            val on = before.copy(fpsTestCard = true)
            assertEquals(setOf(LiveTuning.Step.FPS_TEST), LiveTuning.steps(on, on.copy(fpsTestCardFps = 12)))
            assertEquals(setOf(LiveTuning.Step.FPS_TEST), LiveTuning.steps(on, on.copy(fpsTestCard = false)))
        }
        assertEquals("the pace of a card that is off changes nothing", emptySet<LiveTuning.Step>(), LiveTuning.steps(before, before.copy(fpsTestCardFps = 12)))
        // It is not a new stream, nor a new Android Auto session, nor a fit: the stream's own settings do not carry it.
        val steps = LiveTuning.steps(before, before.copy(fpsTestCard = true, fpsTestCardFps = 10))
        assertTrue(steps.toString(), steps.none { it == LiveTuning.Step.NEW_STREAM || it == LiveTuning.Step.NEW_ANDROID_AUTO_SESSION || it == LiveTuning.Step.REFIT })
    }

    private val panel = File("src/main/java/com/reau/app/ui/FitPanel.kt").readText()

    @Test
    fun `the section is inside the debug check, after the stats and the button sections, with the switch and the five rates`() {
        val debug = panel.substringAfter("if (com.reau.app.BuildConfig.DEBUG) {").substringBefore("\n        }\n")
        val order = listOf("StatsSection(s, change)", "ButtonSection(s, change)", "FpsTestCardSection(s, change)").map { debug.indexOf(it) }
        assertTrue("the three sections in this order inside the check: $order", order.all { it >= 0 } && order == order.sorted())
        assertEquals("the section is called from there only", 1, Regex("""FpsTestCardSection\(s, change\)""").findAll(panel).count())
        val block = panel.substringAfter("private fun FpsTestCardSection(").substringBefore("\n}\n")
        assertTrue(block, block.contains("GroupLabel(null, stringResource(R.string.section_fps_test_card))"))
        assertTrue(block, block.contains("\"fps_test_card\","))
        assertTrue(block, block.contains("info = Info("))
        assertTrue(block, block.contains("if (s.fpsTestCard) {"))
        assertTrue("one segment each, tagged by its value", block.contains("REauSettings.FPS_TEST_CARD_RATES.map { it.toString() to \"fps_test_card_rate_\$it\" }"))
        assertTrue(block, block.contains("tag = \"fps_test_card_rate\""))
        assertTrue("a tap picks a value of the list", block.contains("change(s.copy(fpsTestCardFps = REauSettings.FPS_TEST_CARD_RATES[it]))"))
        assertFalse("no place and no size", block.contains("FineStepper"))
    }

    @Test
    fun `the texts are in every language`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val text = File("src/main/res/$folder/strings.xml").readText()
            for (name in listOf("section_fps_test_card", "fps_test_card_title", "fps_test_card_info", "fps_test_card_rate_title")) {
                assertTrue("$folder: $name", Regex("""<string name="$name">.+</string>""").containsMatchIn(text))
            }
        }
        // The pace chosen is said to count only while the test is on.
        assertTrue(File("src/main/res/values-pt/strings.xml").readText().contains("O ritmo escolhido vale só enquanto o teste estiver ligado."))
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("The pace you pick applies only while the test is on."))
        assertTrue(File("src/main/res/values-es/strings.xml").readText().contains("El ritmo elegido vale solo mientras la prueba esté activada."))
        val pt = File("src/main/res/values-pt/strings.xml").readText()
        assertTrue(pt.contains("""<string name="fps_test_card_title">Quadro de teste de FPS</string>"""))
        assertTrue(pt.contains("""<string name="fps_test_card_rate_title">Ritmo do teste</string>"""))
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("""<string name="fps_test_card_rate_title">Test rate</string>"""))
        assertTrue(File("src/main/res/values-es/strings.xml").readText().contains("""<string name="fps_test_card_rate_title">Ritmo de la prueba</string>"""))
    }
}
