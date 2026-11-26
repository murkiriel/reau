package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every setting that is "experimental" or "de teste" has its letters in red, to say it is not yet the app's standard
 * and may be removed. A row is marked by what its own title and line say, so a new experiment is red by writing
 * "Experimental" or "(teste)" in its text, and nothing has to be remembered elsewhere.
 */
class ExperimentalMarkTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()

    private fun text(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("sem o texto $name")

    @Test
    fun `a row that says experimental or test is marked`() {
        assertTrue(ExperimentalMark.marks("Capa do álbum", "Experimental: a imagem no cartão de música"))
        assertTrue(ExperimentalMark.marks("Protocolo 1.7 do Android Auto (teste)", null))
        assertTrue(ExperimentalMark.marks("SPS de baixa latência (teste)", ""))
        assertTrue("any case", ExperimentalMark.marks("EXPERIMENTAL", null))
        assertTrue("in the title, in brackets", ExperimentalMark.marks("Retomar se o sistema fechar o REau (experimental)", null))
    }

    @Test
    fun `the app's own rows are not marked, not even by words that only look alike`() {
        assertFalse(ExperimentalMark.marks("Registro de diagnóstico", "Gravando no celular"))
        assertFalse(ExperimentalMark.marks("Gravar o vídeo enviado ao painel", "Para depuração"))
        assertFalse("plural, a section's line, not a setting that is a test", ExperimentalMark.marks("Depuração", "Testes, registros e gravações"))
        assertFalse(ExperimentalMark.marks("Ligar o servidor de rede", null))
        assertFalse(ExperimentalMark.marks(null, null))
    }

    @Test
    fun `the Hindi word for experimental marks a row, where a plain word boundary would miss it`() {
        // 26/11/2026: "प्रायोगिक" ends in a letter that takes vowel signs, which a \b does not treat as part of the word.
        assertTrue(ExperimentalMark.marks("डैश को जाने वाले पैकेट के बीच अंतर रखें (प्रायोगिक)", null))
        assertTrue(ExperimentalMark.marks(null, "प्रायोगिक: एक परीक्षण"))
        assertTrue(ExperimentalMark.marks("(प्रायोगिक)"))
        assertFalse("a longer word that only contains it", ExperimentalMark.marks("अप्रायोगिक", "प्रायोगिकता"))
        assertFalse("a Latin letter stuck to it", ExperimentalMark.marks("xप्रायोगिक"))
        assertFalse("the plural of the Portuguese word is still not marked", ExperimentalMark.marks("Testes", "experimentais"))
    }

    private fun redNames(folder: String): Set<String> {
        val xml = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .filter { it.groupValues[1].endsWith("_sub") || it.groupValues[1].endsWith("_title") }
            .filter { ExperimentalMark.marks(it.groupValues[2]) }
            .map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `exactly the rows the app calls experimental are red today, in the four languages`() {
        // 26/11/2026 (the owner's request): what 0.4.0 added and the bike has not yet proved is red and says "(experimental)" in its title:
        // "Retomar se o sistema fechar o REau" and "Espaçar os pacotes para o painel". The other rows are plain ("Anunciar como moto" and the ring included).
        val experimental = setOf("resume_after_kill_title", "pace_packets_title")
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) assertEquals(folder, experimental, redNames(folder))
        assertTrue(text("resume_after_kill_title").endsWith("(experimental)"))
        assertTrue(text("pace_packets_title").endsWith("(experimental)"))
        assertFalse("the motorcycle row is a normal one", ExperimentalMark.marks(text("announce_motorcycle_title"), text("announce_motorcycle_sub")))
        // A row becomes red again only by saying so in its own text (a new experiment); the ring's line does not.
        assertFalse(ExperimentalMark.marks(text("ring_title"), text("ring_sub")))
        assertFalse("the real route has no row any more", strings.contains("name=\"route_extras_sub\""))
    }

    @Test
    fun `the Hindi titles of the two rows say prayogik, so they are red as the others`() {
        val hindi = File("src/main/res/values-hi/strings.xml").readText()
        for (name in listOf("resume_after_kill_title", "pace_packets_title")) {
            val title = Regex("""<string name="$name">(.*?)</string>""").find(hindi)!!.groupValues[1]
            assertTrue("$name: $title", title.endsWith("(प्रायोगिक)"))
            assertTrue(name, ExperimentalMark.marks(title))
        }
    }

    @Test
    fun `the long texts of the two rows say it is a test that may go in a later version, and stay within the rule of the (i)`() {
        // 26/11/2026: the (i) of each experimental row tells the rider what "experimental" means; the length is InfoTextsTest's rule (3 sentences, 100 words).
        val said = mapOf(
            "values" to "is a test and may be removed in a later version", "values-pt" to "é um teste e pode sair numa próxima versão",
            "values-es" to "es una prueba y puede salir en una próxima versión", "values-hi" to "यह एक परीक्षण है और अगले संस्करण में हट सकता है",
        )
        for ((folder, phrase) in said) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            for (name in listOf("resume_after_kill", "pace_packets")) {
                val long = Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertTrue("$folder/$name: $long", phrase in long)
            }
        }
    }

    @Test
    fun `there is no legend on the settings, the red letters stand for themselves`() {
        // The red line at the top explaining the red toggles is gone.
        assertFalse(strings.contains("name=\"experimental_legend\""))
    }

    @Test
    fun `the section is called Depuração and nothing is called advanced any more`() {
        assertEquals("Depuração", text("debug_title"))
        assertFalse(ExperimentalMark.marks(text("debug_title"), text("debug_sub")))
        assertFalse("nothing is called Avançado any more in the app's texts", strings.contains("Avançado", ignoreCase = true))
    }
}
