package com.reau.app.devtools

import com.reau.app.dash.nav.DashManeuverGlyphs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug build's tools at the end of Ajustes > Depuração (P32A): in three groups, each with a title and a line that says what it does and when to use it, in
 * pt, en and es; their texts in `src/debug/res` (a release does not carry them) and none typed in the code; the arrow test's label in the three languages.
 */
class DebugTriggersTextsTest {
    private val root = File("src")
    private val folders = mapOf("en" to "values", "pt" to "values-pt", "es" to "values-es")

    private fun strings(folder: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(root, "debug/res/$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val keys = listOf(
        "debug_warn_title", "debug_warn_sub", "debug_warn_dash_stuck", "debug_warn_phone_hot", "debug_warn_no_dash", "debug_warn_channel",
        "debug_fault_title", "debug_fault_sub", "debug_fault_decoder", "debug_fault_keyframe",
        "debug_fault_decoder_what", "debug_fault_keyframe_what",
        "debug_arrow_title", "debug_arrow_sub_off", "debug_arrow_sub_idle", "debug_arrow_sending", "debug_arrow_previous", "debug_arrow_next",
        "debug_arrow_stop", "debug_arrow_unnamed", "debug_arrow_hidden",
    )

    @Test
    fun `every text exists in the three languages, is not the English one in the other two, and the three files have the same names`() {
        val en = strings("values")
        for ((lang, folder) in folders) {
            val texts = strings(folder)
            assertEquals(lang, en.keys, texts.keys)
            for (key in keys) {
                val text = texts[key]
                assertNotNull("$lang/$key", text)
                assertTrue("$lang/$key is empty", text!!.isNotBlank())
                if (lang != "en") assertNotEquals("$lang/$key is the English text", en.getValue(key), text)
            }
        }
        assertEquals(keys.sorted(), en.keys.sorted())
    }

    @Test
    fun `the three groups have the titles and lines the owner asked for`() {
        val pt = strings("values-pt")
        assertEquals("Testar os avisos", pt["debug_warn_title"])
        assertEquals("Cada botão dispara a notificação real. Confira o som, a vibração com a tela apagada e no bolso, e o canal.", pt["debug_warn_sub"])
        assertEquals(listOf("Painel travado", "Celular quente", "Painel sumiu, projeção parada", "Abrir o canal de alertas"),
            listOf("debug_warn_dash_stuck", "debug_warn_phone_hot", "debug_warn_no_dash", "debug_warn_channel").map { pt[it] })
        assertEquals("Simular falhas", pt["debug_fault_title"])
        assertEquals("Com a projeção rodando. O REau deve se recuperar sozinho.", pt["debug_fault_sub"])
        assertEquals(listOf("Travar o decodificador", "Pedir um quadro completo"),
            listOf("debug_fault_decoder", "debug_fault_keyframe").map { pt[it] })
        assertEquals("Testar as setas do painel", pt["debug_arrow_title"])
        assertTrue(pt.getValue("debug_arrow_sub_idle").contains("Percorra as setas de manobra e fotografe o painel com a moto parada; Parar devolve a seta real."))
        assertEquals(listOf("Anterior", "Próxima", "Parar"), listOf("debug_arrow_previous", "debug_arrow_next", "debug_arrow_stop").map { pt[it] })
        val en = strings("values")
        assertEquals(listOf("Test the warnings", "Simulate failures", "Test the dash arrows"), listOf("debug_warn_title", "debug_fault_title", "debug_arrow_title").map { en[it] })
        val es = strings("values-es")
        assertEquals(listOf("Probar los avisos", "Simular fallos", "Probar las flechas del tablero"), listOf("debug_warn_title", "debug_fault_title", "debug_arrow_title").map { es[it] })
    }

    @Test
    fun `the arrow test's sending line takes the label`() {
        for ((lang, folder) in folders) assertTrue(lang, strings(folder).getValue("debug_arrow_sending").contains("%1\$s"))
    }

    @Test
    fun `the code has no English text typed in it, only resources`() {
        val code = File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
        val text = code.joinToString("\n")
        for (old in listOf("Fire a warning", "Dash stuck", "Phone hot", "Inject a fault", "Decoder stalls", "Encoder mute", "Calar o codificador", "Focus cycle", "Arrow test", "Previous", "Next", "Stop")) {
            assertFalse("still typed in: $old", text.contains("\"$old"))
        }
        for (key in keys.filter { !it.endsWith("_what") && it != "debug_arrow_sub_off" && it != "debug_arrow_sending" && it != "debug_arrow_unnamed" && it != "debug_arrow_hidden" }) {
            assertTrue("$key is not used", Regex("""R\.string\.$key(?![A-Za-z_])""").containsMatchIn(text))
        }
    }

    @Test
    fun `the tags the screen tests look for are all still there`() {
        val text = File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readText()
        for (tag in listOf(
            "debug_fire_dash_stuck", "debug_fire_phone_hot", "debug_fire_no_dash", "debug_alert_channel", "debug_fire", "debug_fault_decoder",
            "debug_fault_focus", "debug_fault", "debug_arrow_previous", "debug_arrow_next", "debug_arrow_stop", "debug_arrow", "debug_arrow_off",
        )) assertTrue(tag, text.contains("\"$tag\""))
    }

    @Test
    fun `the three groups come in this order, warnings then faults then arrows`() {
        val text = File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readText()
        val order = listOf("debug_warn_title", "debug_fault_title", "ArrowTestRow() }").map { text.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
    }

    @Test
    fun `none of these texts is in the main resources, so a release does not carry them`() {
        for (folder in folders.values) {
            val main = File(root, "main/res/$folder/strings.xml").readText()
            for (key in keys) assertFalse("$folder/$key is in main", main.contains("\"$key\""))
        }
        assertTrue(File(root, "debug/res/values/strings.xml").exists())
        assertFalse(File(root, "release/res").exists())
    }

    // ── the label of the arrow test, in the three languages ──

    private val words = ArrowTrial.LabelWords("hides the bubble", "no name yet")

    @Test
    fun `the label is the byte in hex and its name in the language asked`() {
        assertEquals("0x2D  Bear off to the right", ArrowTrial.label(0x2D, "en", words))
        assertEquals("0x2D  Abrir à direita", ArrowTrial.label(0x2D, "pt", words))
        assertEquals("0x2D  Desviarse a la derecha", ArrowTrial.label(0x2D, "es", words))
        assertEquals("a language the app has no words for reads English", "0x2D  Bear off to the right", ArrowTrial.label(0x2D, "fr", words))
    }

    @Test
    fun `a byte with no name and the hidden bubble use the words of the screen`() {
        assertEquals("0x41  no name yet", ArrowTrial.label(0x41, "en", words))
        assertEquals("0x%02X  hides the bubble".format(DashManeuverGlyphs.HIDDEN_BUBBLE), ArrowTrial.label(DashManeuverGlyphs.HIDDEN_BUBBLE, "es", words))
    }

    @Test
    fun `the Spanish names cover every byte that has an English one, and only those`() {
        for (code in 0..0xFF) {
            val en = DashManeuverGlyphs.describe(code)
            val es = ArrowNamesEs.name(code)
            if (en == null) assertNull("0x%02X".format(code), es) else assertNotNull("0x%02X has no Spanish name".format(code), es)
        }
    }

    @Test
    fun `the roundabouts' Spanish names carry the exit number`() {
        assertEquals("Rotonda horaria, salida 1", ArrowNamesEs.name(0x0B))
        assertEquals("Rotonda antihoraria, salida 2", ArrowNamesEs.name(0x33))
    }

    @Test
    fun `the log keeps the label in two languages`() {
        assertEquals("0x2D  Bear off to the right  (Abrir à direita)", ArrowTrial.label(0x2D))
    }
}
