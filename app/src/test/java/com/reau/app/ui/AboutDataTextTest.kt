package com.reau.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "O REau e os seus dados" (short texts, and no "Limites" part): every part text stays short
 * enough to read at a glance, and the page keeps the parts the privacy answer needs. The texts are in strings.xml.
 */
class AboutDataTextTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `no part is longer than a short paragraph`() {
        for (part in listOf("what", "leaves", "stays", "kept", "control", "reach", "web")) {
            val body = text("about_data_$part")
            assertTrue("$part tem ${body.length} caracteres: $body", body.length <= 340)
        }
    }

    @Test
    fun `the Limites part is gone`() {
        assertFalse(strings.contains("about_data_limits"))
        assertFalse(strings.contains(">Limites<"))
    }

    @Test
    fun `the page says what the web server sends, to whom, and that it is not encrypted`() {
        val web = text("about_data_web")
        assertTrue(web, web.contains("Ligar o servidor de rede") && web.contains("desligado de fábrica"))
        assertTrue(web, web.contains("PIN") && web.contains("mesma rede") && web.contains("sem criptografia"))
        assertTrue("the paragraph that says what leaves the phone points to the panel only, the web has its own part", !text("about_data_leaves").contains("Só para o painel"))
        assertTrue(text("about_data_web_title"), text("about_data_web_title").contains("navegador"))
        assertTrue(text("permissions_intro"), text("permissions_intro").contains("servidor de rede"))
    }

    @Test
    fun `the shorter texts keep what the privacy answer says`() {
        val leaves = text("about_data_leaves")
        // One short sentence (28/10/2026): the dash, with the switches that are on, and on the internet only the weather and the update check
        // on a tap; the details (Open-Meteo, 11 km, the Wi-Fi's password, GitHub) are in the privacy page of the site, which has them.
        assertTrue(leaves, leaves.contains("painel") && leaves.contains("clima") && leaves.contains("verificação de atualização") && leaves.contains("ao tocar"))
        assertTrue(text("about_data_stays"), text("about_data_stays").contains("registro de diagnóstico") && text("about_data_stays").contains("posição"))
        // 28/10/2026: the optional recordings, off in the app you ride with, are said to hold what the log does not.
        assertTrue(text("about_data_stays"), text("about_data_stays").contains("gravações opcionais") && text("about_data_stays").contains("desligadas"))
        val kept = text("about_data_kept")
        assertTrue(kept, kept.contains("senha") && kept.contains("Wi-Fi") && kept.contains("cifrados") && kept.contains("posição") && kept.contains("clima"))
        assertTrue(text("about_data_control"), text("about_data_control").contains("Depuração") && text("about_data_control").contains("apaga"))
    }
}
