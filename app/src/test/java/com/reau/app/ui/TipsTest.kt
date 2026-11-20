package com.reau.app.ui

import com.reau.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tips page (20/11/2026): the tips are declared in one list ([Tips.ALL]), each with its title and text in the three languages, and
 * the lightbulb in the main screen's bar opens the page, in the release build too. The screens are read from their source, as the other
 * tests of this folder do (there is no Compose test on the JVM); the click itself is TipsScreenTest's, on a device.
 */
class TipsTest {
    private val src = File("src/main")
    private val screen = File(src, "java/com/reau/app/ui/TipsScreen.kt").readText()
    private val main = File(src, "java/com/reau/app/ui/MainScreen.kt").readText()
    private val activity = File(src, "java/com/reau/app/MainActivity.kt").readText()
    private fun strings(folder: String) = File(src, "res/$folder/strings.xml").readText()
    private fun text(folder: String, name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings(folder))?.groupValues?.get(1)

    @Test
    fun `the list is not empty, its tags are not repeated and the first tip is the Waze zoom`() {
        assertTrue(Tips.ALL.isNotEmpty())
        assertEquals(Tips.ALL.map { it.tag }.distinct(), Tips.ALL.map { it.tag })
        val first = Tips.ALL.first()
        assertEquals("waze_zoom", first.tag)
        assertEquals(R.string.tip_waze_zoom_title, first.title)
        assertEquals(R.string.tip_waze_zoom_text, first.text)
    }

    @Test
    fun `every tip of the list has a title and a text in the three languages, and the list is the only place that names them`() {
        val declared = Regex("""Tip\("(\w+)", R\.string\.tip_(\w+)_title, R\.string\.tip_(\w+)_text""").findAll(screen).toList()
        assertEquals("one line of the list for each tip", Tips.ALL.size, declared.size)
        for (d in declared) {
            val (tag, title, text) = d.destructured
            assertEquals("the title and the text of $tag are named after it", listOf(tag, tag), listOf(title, text))
            for (folder in listOf("values", "values-pt", "values-es")) {
                assertTrue("$folder: title of $tag", !text(folder, "tip_${tag}_title").isNullOrBlank())
                assertTrue("$folder: text of $tag", !text(folder, "tip_${tag}_text").isNullOrBlank())
            }
        }
        assertEquals(
            "no string of a tip that the list does not use", declared.size,
            Regex("""name="tip_\w+_title"""").findAll(strings("values")).count(),
        )
    }

    @Test
    fun `the Waze tip says what was asked in the three languages`() {
        val pt = text("values-pt", "tip_waze_zoom_title")!! to text("values-pt", "tip_waze_zoom_text")!!
        assertEquals("Desative o zoom automático do Waze", pt.first)
        assertTrue(pt.second, pt.second.startsWith("Recomendamos desligar o zoom automático nas configurações do Waze."))
        assertTrue(pt.second, pt.second.contains("mais pesado para o painel") && pt.second.contains("pequena demais"))
        val en = text("values", "tip_waze_zoom_text")!!
        assertTrue(en, en.contains("automatic zoom") && en.contains("Waze") && en.contains("heavier") && en.contains("too small"))
        val es = text("values-es", "tip_waze_zoom_text")!!
        assertTrue(es, es.contains("zoom automático") && es.contains("Waze") && es.contains("más pesado") && es.contains("demasiado pequeña"))
        // The menu path in Waze is not known, so no text names one.
        for (folder in listOf("values", "values-pt", "values-es")) assertFalse(folder, text(folder, "tip_waze_zoom_text")!!.contains(">"))
    }

    @Test
    fun `the button is called Tips in each language`() {
        assertEquals("Tips", text("values", "tips_title"))
        assertEquals("Dicas", text("values-pt", "tips_title"))
        assertEquals("Consejos", text("values-es", "tips_title"))
    }

    @Test
    fun `the main screen's bar has the lightbulb button with its tag, and it opens the page`() {
        val bar = main.substringAfter("REauTopBar(actions = {").substringBefore("})\n")
        assertTrue(bar, bar.contains("extras.onOpenTips"))
        assertTrue(bar, bar.contains("testTag(\"open_tips\")"))
        assertTrue(bar, bar.contains("R.drawable.ic_ms_lightbulb"))
        assertTrue(bar, bar.contains("description = stringResource(R.string.tips_title)"))
        assertTrue("MainActivity opens the page", activity.contains("onOpenTips = { page = SettingsPage.TIPS }"))
        assertTrue(activity.contains("SettingsPage.TIPS -> com.reau.app.ui.TipsScreen(onBack)"))
        assertTrue("the icon is there", File(src, "res/drawable/ic_ms_lightbulb.xml").isFile)
    }

    @Test
    fun `the main screen's top bar draws the actions it is given, at its end`() {
        val bar = File(src, "java/com/reau/app/ui/components/TopBar.kt").readText().substringBefore("        } else {")
        assertTrue(bar, bar.contains("Modifier.align(Alignment.CenterEnd)"))
        assertTrue(bar, bar.contains("{ actions() }"))
    }

    @Test
    fun `the button and the page are in the release build too, not only in the debug one`() {
        for ((name, source) in listOf("MainScreen" to main, "TipsScreen" to screen)) {
            assertFalse("$name is behind the debug flag", source.contains("BuildConfig.DEBUG"))
        }
        val wiring = activity.lines().filter { "TIPS" in it }
        assertTrue(wiring.toString(), wiring.none { "DEBUG" in it })
    }

    @Test
    fun `the page has the top bar with back, and one card for each tip of the list`() {
        assertTrue(screen.contains("REauTopBar(stringResource(R.string.tips_title), onBack, titleTag = \"tips_title\")"))
        assertTrue(screen.contains("for (tip in Tips.ALL) TipCard(tip)"))
        assertTrue(screen.contains("MaterialTheme.shapes.large"))
        assertTrue(screen.contains("MaterialTheme.colorScheme.surfaceContainer"))
    }
}
