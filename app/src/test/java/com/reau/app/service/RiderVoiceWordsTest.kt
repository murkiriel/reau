package com.reau.app.service

import com.reau.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phrases of the voice and the texts of the Wi-Fi notice, in the three languages. */
class RiderVoiceWordsTest {
    private fun strings(folder: String) = File("src/main/res/$folder/strings.xml").readText()

    private fun text(folder: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings(folder))?.groupValues?.get(1)
            ?: error("$folder has no $name")

    private val folders = listOf("values", "values-pt", "values-es")

    @Test
    fun `each cue has a phrase of its own`() {
        val ids = VoiceCue.entries.map { VoiceWords.of(it) }
        assertEquals(VoiceCue.entries.size, ids.toSet().size)
        assertEquals(R.string.voice_dash_gone, VoiceWords.of(VoiceCue.DASH_GONE))
        assertEquals(R.string.voice_dash_back, VoiceWords.of(VoiceCue.DASH_BACK))
        assertEquals(R.string.voice_phone_hot, VoiceWords.of(VoiceCue.PHONE_HOT))
        assertEquals(R.string.voice_wifi_off, VoiceWords.of(VoiceCue.WIFI_OFF))
    }

    @Test
    fun `the phrases are said as the owner chose, in Portuguese, and have an English and a Spanish one`() {
        assertEquals("Painel desconectado. Desligue e ligue a moto.", text("values-pt", "voice_dash_gone"))
        assertEquals("Painel de volta.", text("values-pt", "voice_dash_back"))
        assertEquals("Wi-Fi do celular desligado.", text("values-pt", "voice_wifi_off"))
        // The existing notice does not say the video is lowered (it says to take the phone out of the sun), so the phrase says what the notice says.
        assertTrue(text("values-pt", "voice_phone_hot").startsWith("Celular quente."))
        for (name in listOf("voice_dash_gone", "voice_dash_back", "voice_phone_hot", "voice_wifi_off")) {
            val said = folders.map { text(it, name) }
            assertEquals("$name is different in the three languages: $said", 3, said.toSet().size)
            for (s in said) assertTrue("$name is a short sentence: $s", s.isNotBlank() && s.length < 80 && s.endsWith("."))
        }
    }

    @Test
    fun `no phrase carries a placeholder, since the voice says the words as they are`() {
        for (folder in folders) for (name in listOf("voice_dash_gone", "voice_dash_back", "voice_phone_hot", "voice_wifi_off")) {
            assertFalse("$folder $name", "%" in text(folder, name))
        }
    }

    @Test
    fun `the notice of the Wi-Fi off says what to do in the three languages`() {
        assertEquals("Wi-Fi do celular desligado: ligue o Wi-Fi para conectar ao painel", text("values-pt", "notif_text_wifi_off"))
        assertEquals("Ligar o Wi-Fi", text("values-pt", "action_wifi_on"))
        assertEquals("Phone Wi-Fi is off: turn on Wi-Fi to connect to the dash", text("values", "notif_text_wifi_off"))
        assertEquals("Turn on Wi-Fi", text("values", "action_wifi_on"))
        assertTrue(text("values-es", "notif_text_wifi_off").startsWith("Wi-Fi del teléfono apagado:"))
        assertEquals("Encender el Wi-Fi", text("values-es", "action_wifi_on"))
        for (name in listOf("notif_title_wifi_off", "headline_wifi_off", "sub_wifi_off", "warn_wifi_off_title", "wifi_off_warning")) {
            assertEquals("$name is in the three languages", 3, folders.map { text(it, name) }.toSet().size)
        }
        assertEquals(text("values-pt", "notif_text_wifi_off"), text("values-pt", "wifi_off_warning"))
    }

    @Test
    fun `the faq says the app now tells when the phone's Wi-Fi is off, in the three languages`() {
        val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
        val pt = File(root, "docs/pt/faq.html").readText()
        val en = File(root, "docs/en/faq.html").readText()
        val es = File(root, "docs/es/faq.html").readText()
        assertTrue("Ligar o Wi-Fi" in pt && "Procurando o painel" in pt.substringAfter("id=\"s5-q3\"").substringBefore("</article>"))
        assertTrue("Turn on Wi-Fi" in en.substringAfter("id=\"s5-q3\"").substringBefore("</article>"))
        assertTrue("Encender el Wi-Fi" in es.substringAfter("id=\"s5-q3\"").substringBefore("</article>"))
    }
}
