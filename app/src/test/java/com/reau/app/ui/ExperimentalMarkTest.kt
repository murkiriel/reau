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
    fun `exactly the rows the app calls experimental are red today`() {
        val marked = Regex("""<string name="([a-z_0-9]+)">""").findAll(strings).map { it.groupValues[1] }
            .filter { it.endsWith("_sub") || it.endsWith("_title") }
            .filter { ExperimentalMark.marks(text(it)) }
            .toSet()
        // The experimental switches are gone (20/11/2026) and "Anunciar como moto" is a plain setting: no row is red today, except the one of the 0.3.97 test build
        // (the pilots' "Modo de bloqueio do REau (teste)") and, from the 0.3.100 test build, "Espaçar os pacotes para o painel (experimental)" (the 0.4.0's, not yet proved on the bike) and "Teto do quadro-chave para o painel (experimental)".
        assertEquals(setOf("lock_mode_title", "pace_packets_title", "keyframe_cap_title"), marked)
        assertTrue(text("pace_packets_title").endsWith("(experimental)"))
        assertTrue(text("keyframe_cap_title").endsWith("(experimental)"))
        assertFalse("the motorcycle row is a normal one", ExperimentalMark.marks(text("announce_motorcycle_title"), text("announce_motorcycle_sub")))
        // A row becomes red again only by saying so in its own text (a new experiment); the ring's line does not.
        assertFalse(ExperimentalMark.marks(text("ring_title"), text("ring_sub")))
        assertFalse("the real route has no row any more", strings.contains("name=\"route_extras_sub\""))
    }

    @Test
    fun `the long text of the packet spacing and of the keyframe cap says in each language that it is a test that may go in a later version`() {
        // The (i) of an experimental row tells the rider what "experimental" means; the length is InfoTextsTest's rule (3 sentences, 100 words).
        val said = mapOf(
            "values" to "is a test and may be removed in a later version", "values-pt" to "é um teste e pode sair numa próxima versão",
            "values-es" to "es una prueba y puede salir en una próxima versión",
        )
        for (name in listOf("pace_packets", "keyframe_cap")) {
            for ((folder, phrase) in said) {
                val xml = File("src/main/res/$folder/strings.xml").readText()
                val long = Regex("""<string name="$name">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertTrue("$folder $name: $long", phrase in long)
                val title = Regex("""<string name="${name}_title">(.*?)</string>""").find(xml)!!.groupValues[1]
                assertTrue("$folder $name: $title", ExperimentalMark.marks(title))
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
