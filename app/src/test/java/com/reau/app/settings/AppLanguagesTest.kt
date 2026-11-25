package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A escolha de idioma: como uma etiqueta de idioma do Android vira uma das cinco opcoes do app (Auto e os quatro idiomas). */
class AppLanguagesTest {
    @Test
    fun `qualquer portugues e portugues, em qualquer caixa`() {
        for (tag in listOf("pt", "pt-BR", "pt-PT", "PT", "pt_BR", "Pt-br")) {
            assertEquals(tag, AppLanguage.PT, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `qualquer ingles e ingles`() {
        for (tag in listOf("en", "en-US", "en-GB", "EN", "en_AU")) {
            assertEquals(tag, AppLanguage.EN, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `qualquer espanhol e espanhol`() {
        for (tag in listOf("es", "es-CO", "es-ES", "es-419", "ES", "es_MX", "Es-ar")) {
            assertEquals(tag, AppLanguage.ES, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `qualquer hindi e hindi`() {
        for (tag in listOf("hi", "hi-IN", "HI", "hi_IN", "Hi-in")) {
            assertEquals(tag, AppLanguage.HI, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `o app tem Auto e quatro idiomas, e o hindi e o ultimo`() {
        assertEquals(listOf("", "pt-BR", "en", "es", "hi"), AppLanguage.entries.map { it.tag })
    }

    @Test
    fun `sem etiqueta, ou com um idioma que o app nao tem, vale o do telefone`() {
        for (tag in listOf(null, "", "  ", "\t", "fr-FR", "de", "ja", "zh-Hans", "xx")) {
            assertEquals("'$tag'", AppLanguage.AUTO, AppLanguages.fromTag(tag))
        }
    }

    @Test
    fun `as etiquetas de cada idioma voltam ao mesmo idioma`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguages.fromTag(language.tag))
        }
    }

    @Test
    fun `as etiquetas gravadas sao as que o Android entende`() {
        assertEquals("", AppLanguage.AUTO.tag)
        assertEquals("pt-BR", AppLanguage.PT.tag)
        assertEquals("en", AppLanguage.EN.tag)
        assertEquals("es", AppLanguage.ES.tag)
        assertEquals("hi", AppLanguage.HI.tag)
    }

    @Test
    fun `o Android conhece os quatro idiomas do app pela lista de locais, e o seletor tem o nome de cada um`() {
        val locales = Regex("""<locale android:name="([^"]+)" />""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("en", "pt-BR", "es", "hi"), locales)
        assertEquals("a lista de locais tem os idiomas do app, sem o Auto", AppLanguage.entries.filter { it != AppLanguage.AUTO }.map { it.tag }.toSet(), locales.toSet())
        val names = File("src/main/res/values/strings_language.xml").readText()
        for (language in AppLanguage.entries.filter { it != AppLanguage.AUTO }) {
            assertTrue(language.name, Regex("""<string name="language_${language.name.lowercase()}" translatable="false">[^<]+</string>""").containsMatchIn(names))
        }
        assertTrue("o nome do hindi no seletor, na propria escrita", names.contains(">\u0939\u093F\u0928\u094D\u0926\u0940</string>"))
        val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()
        for (language in AppLanguage.entries.filter { it != AppLanguage.AUTO }) {
            assertTrue(language.name, "AppLanguage.${language.name} to R.string.language_${language.name.lowercase()}" in screen)
        }
    }
}
