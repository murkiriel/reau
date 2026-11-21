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
    fun `the third tip is the one about the video not going past 6 fps, right after the Gemini one`() {
        assertEquals(listOf("waze_zoom", "gemini_swap", "video_fps"), Tips.ALL.map { it.tag })
        val tip = Tips.ALL[2]
        assertEquals(R.string.tip_video_fps_title, tip.title)
        assertEquals(R.string.tip_video_fps_text, tip.text)
        assertTrue("the list names it", screen.contains("Tip(\"video_fps\", R.string.tip_video_fps_title, R.string.tip_video_fps_text"))
        assertTrue("after the Gemini one", screen.indexOf("Tip(\"gemini_swap\"") in 0 until screen.indexOf("Tip(\"video_fps\""))
    }

    @Test
    fun `the second tip is the Gemini swap, between the Waze one and the video one`() {
        assertEquals(listOf("waze_zoom", "gemini_swap"), Tips.ALL.map { it.tag }.take(2))
        val tip = Tips.ALL[1]
        assertEquals(R.string.tip_gemini_swap_title, tip.title)
        assertEquals(R.string.tip_gemini_swap_text, tip.text)
        assertTrue("the list names it", screen.contains("Tip(\"gemini_swap\", R.string.tip_gemini_swap_title, R.string.tip_gemini_swap_text"))
        assertTrue("after the Waze one", screen.indexOf("Tip(\"waze_zoom\"") in 0 until screen.indexOf("Tip(\"gemini_swap\""))
    }

    @Test
    fun `the Gemini tip says what was asked in the three languages, and points at the screen, the mode and the button`() {
        assertEquals("Sem intercomunicador? Troque o Gemini", text("values-pt", "tip_gemini_swap_title"))
        assertEquals(
            "O Gemini conversa por voz e precisa de um intercomunicador no capacete. Se você não tem um, troque na tela Joystick do painel " +
                "(Controle de mídia, botão “Pra baixo”) a ação do Gemini por “Alternar mapa e música”: um toque leva do mapa para o app de música e de volta.",
            text("values-pt", "tip_gemini_swap_text"),
        )
        // The names it points at are the ones the joystick screen shows, in each language.
        for ((folder, title) in mapOf("values" to "Dash joystick", "values-pt" to "Joystick do painel", "values-es" to "Joystick del tablero")) {
            val body = text(folder, "tip_gemini_swap_text")!!
            assertEquals(title, text(folder, "joystick_title"))
            assertTrue("$folder: $body", body.contains(title))
            for (name in listOf("joystick_mode_media", "joystick_control_down", "joystick_action_swap_map_music")) {
                assertTrue("$folder: the tip names ${text(folder, name)}", body.contains(text(folder, name)!!))
            }
            assertTrue(body, body.contains("Gemini"))
        }
        assertEquals("Not using an intercom? Swap Gemini", text("values", "tip_gemini_swap_title"))
        assertTrue(text("values-es", "tip_gemini_swap_text")!!.contains("tablero"))
        assertTrue(text("values-es", "tip_gemini_swap_title")!!.contains("Gemini"))
    }

    @Test
    fun `the video tip says what was asked in the three languages and names no firmware or chip`() {
        assertEquals("Por que o vídeo não passa de 6 fps", text("values-pt", "tip_video_fps_title"))
        assertEquals(
            "O painel aceita mais quadros por segundo, mas o mesmo processador que mostra o vídeo também desenha a tela e atende os botões. " +
                "Nos testes na moto, a 8 fps o ícone da reserva passou a piscar e a 12 fps o painel começou a ignorar os botões. " +
                "A 6 fps tudo funcionou sem falhas, por isso o REau nunca passa disso. " +
                "Mesmo assim, o app vem com o ritmo Original (4 fps no A, 2 no B), mais leve para o painel; para uma imagem mais fluida, escolha o REau (6 fps no A, 3 no B) no ajuste \\\"Ritmo do vídeo\\\".",
            text("values-pt", "tip_video_fps_text"),
        )
        val en = text("values", "tip_video_fps_title")!! to text("values", "tip_video_fps_text")!!
        assertTrue(en.toString(), en.first.contains("6 fps") && en.second.contains("8 fps") && en.second.contains("12 fps") && en.second.contains("REau"))
        assertTrue(en.second, en.second.contains("reserve icon") && en.second.contains("ignore the buttons"))
        val es = text("values-es", "tip_video_fps_title")!! to text("values-es", "tip_video_fps_text")!!
        assertTrue(es.toString(), es.first.contains("6 fps") && es.second.contains("8 fps") && es.second.contains("12 fps") && es.second.contains("REau"))
        assertTrue(es.second, es.second.contains("tablero") && es.second.contains("reserva") && es.second.contains("ignorar los botones"))
        // The repository is public: nothing of the dash's firmware (its decoder, its chip, its software) is in the tip.
        val firmware = Regex("""\b(?:firmware|decoder|decodificador|software|chip|SoC|ARM|Cortex|MCU|Linux|hardware)\b""", RegexOption.IGNORE_CASE)
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in listOf("tip_video_fps_title", "tip_video_fps_text")) {
                val body = text(folder, name)!!
                assertFalse("$folder/$name: $body", firmware.containsMatchIn(body))
            }
        }
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
        val bar = File(src, "java/com/reau/app/ui/components/TopBar.kt").readText().substringBefore("\n        } else {")
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
