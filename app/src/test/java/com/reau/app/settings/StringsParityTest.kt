package com.reau.app.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's texts in its four languages (English is the base, `values/`; Portuguese is `values-pt/`; Spanish is `values-es/`; Hindi is `values-hi/`, not yet
 * read by a native speaker): every text is in all four, with the same placeholders (and the same escapes, tags and counts of lines, in Hindi too), and no English text is still Portuguese. A text added in one language only, or with a
 * `%1$s` lost in the translation, would show as a crash or as a gap in one language only.
 */
class StringsParityTest {
    private class Text(val name: String, val translatable: Boolean, val value: String, val plural: Boolean = false)

    private fun read(folder: String): Map<String, Text> {
        val files = File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
        val texts = mutableMapOf<String, Text>()
        for (file in files) {
            val xml = file.readText()
            Regex("""<string name="([^"]+)"( translatable="false")?>(.*?)</string>""").findAll(xml).forEach {
                texts[it.groupValues[1]] = Text(it.groupValues[1], it.groupValues[2].isEmpty(), it.groupValues[3])
            }
            Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                texts[it.groupValues[1]] = Text(it.groupValues[1], true, it.groupValues[2], plural = true)
            }
        }
        return texts
    }

    private val english = read("values")
    private val portuguese = read("values-pt")
    private val spanish = read("values-es")
    private val hindi = read("values-hi")
    private val placeholder = Regex("""%(?:\d+\$)?[sdfx%]""")

    @Test
    fun `every translatable text is in both languages`() {
        val translatableEnglish = english.filterValues { it.translatable }.keys
        val translatablePortuguese = portuguese.filterValues { it.translatable }.keys
        assertEquals("only in English: ${translatableEnglish - translatablePortuguese}", emptySet<String>(), translatableEnglish - translatablePortuguese)
        assertEquals("only in Portuguese: ${translatablePortuguese - translatableEnglish}", emptySet<String>(), translatablePortuguese - translatableEnglish)
        assertTrue(translatableEnglish.size > 600)
    }

    @Test
    fun `a text keeps its placeholders in the translation`() {
        for ((name, pt) in portuguese) {
            val en = english[name] ?: continue
            fun marks(text: Text) = placeholder.findAll(text.value).map { it.value }.sorted().toList().let { if (pt.plural) it.distinct() else it }
            assertEquals("$name: placeholders", marks(pt), marks(en))
        }
    }

    @Test
    fun `no English text is still Portuguese`() {
        val portugueseLetters = Regex("[ãõçÃÕÇ]")
        val portugueseWords = Regex("""\b(você|não|para o|para a|com o|com a|uma|isso|está|são|painel|ligação|moto|ajustes|chave|quando|enquanto)\b""", RegexOption.IGNORE_CASE)
        val names = setOf("terms_lang_pt")
        for ((name, en) in english) {
            if (!en.translatable || name in names) continue
            assertTrue("$name looks Portuguese: ${en.value.take(80)}", !portugueseLetters.containsMatchIn(en.value) && !portugueseWords.containsMatchIn(en.value))
        }
    }

    @Test
    fun `an English text is not the Portuguese one, except names and numbers that are the same word in both`() {
        val same = setOf(
            "app_name", "language_pt", "language_en", "language_es", "terms_lang_pt", "terms_lang_en", "terms_lang_es",
        )
        val equal = english.filter { (name, en) -> en.translatable && name !in same && portuguese[name]?.value == en.value && en.value.any { it.isLetter() } }
        // Words that are the same in both languages (Wi-Fi, Android Auto, Gemini, ...) are fine; this list only shows what to look at.
        assertTrue("same in both languages, check they are not untranslated: ${equal.keys}", equal.size < 60)
    }

    @Test
    fun `every translatable text is in Spanish too`() {
        val translatableEnglish = english.filterValues { it.translatable }.keys
        val translatableSpanish = spanish.filterValues { it.translatable }.keys
        assertEquals("only in English: ${translatableEnglish - translatableSpanish}", emptySet<String>(), translatableEnglish - translatableSpanish)
        assertEquals("only in Spanish: ${translatableSpanish - translatableEnglish}", emptySet<String>(), translatableSpanish - translatableEnglish)
    }

    @Test
    fun `a text keeps its placeholders in the Spanish translation`() {
        for ((name, es) in spanish) {
            val en = english[name] ?: continue
            fun marks(text: Text) = placeholder.findAll(text.value).map { it.value }.sorted().toList().let { if (es.plural) it.distinct() else it }
            assertEquals("$name: placeholders", marks(es), marks(en))
        }
    }

    @Test
    fun `no Spanish text is Portuguese`() {
        val portugueseLetters = Regex("[ãõÃÕ]")
        val portugueseWords = Regex("""\b(você|não|isso|são|painel|ligação|enquanto)\b""", RegexOption.IGNORE_CASE)
        for ((name, es) in spanish) {
            if (!es.translatable) continue
            assertTrue("$name looks Portuguese: ${es.value.take(80)}", !portugueseLetters.containsMatchIn(es.value) && !portugueseWords.containsMatchIn(es.value))
        }
    }

    @Test
    fun `a Spanish text is not the English one, except names and numbers that are the same word in both`() {
        val same = setOf("app_name", "language_pt", "language_en", "language_es", "terms_lang_pt", "terms_lang_en", "terms_lang_es")
        val equal = english.filter { (name, en) -> en.translatable && name !in same && spanish[name]?.value == en.value && en.value.any { it.isLetter() } }
        // Words that are the same in both languages (Wi-Fi, Android Auto, Gemini, ...) are fine; this list only shows what to look at.
        assertTrue("same in both languages, check they are not untranslated: ${equal.keys}", equal.size < 60)
    }

    @Test
    fun `every translatable text is in Hindi too, and no Hindi text is a name that English does not have`() {
        val translatableEnglish = english.filterValues { it.translatable }.keys
        val translatableHindi = hindi.filterValues { it.translatable }.keys
        assertEquals("only in English: ${translatableEnglish - translatableHindi}", emptySet<String>(), translatableEnglish - translatableHindi)
        assertEquals("only in Hindi: ${translatableHindi - translatableEnglish}", emptySet<String>(), translatableHindi - translatableEnglish)
        assertEquals("a text that English marks translatable=false must not be in Hindi", emptySet<String>(), hindi.keys - translatableEnglish)
    }

    @Test
    fun `every format mark of a Hindi text is the English one, the same number of times`() {
        val formatMark = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXoeEgGcb%nS]""")
        for ((name, hi) in hindi) {
            val en = english[name] ?: continue
            fun marks(text: Text) = formatMark.findAll(text.value).map { it.value }.sorted().toList()
            if (hi.plural) {
                // The plural's items: each one has the marks of the English `other`, and the set of marks is the same.
                val items = Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(hi.value).associate { it.groupValues[1] to it.groupValues[2] }
                assertEquals("$name: Hindi plural forms (one and other only)", setOf("one", "other"), items.keys)
                val enItems = Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(en.value).associate { it.groupValues[1] to it.groupValues[2] }
                for ((quantity, text) in items) {
                    val expected = formatMark.findAll(enItems.getValue("other")).map { it.value }.toSet()
                    assertEquals("$name/$quantity: marks", expected, formatMark.findAll(text).map { it.value }.toSet())
                }
            } else {
                assertEquals("$name: format marks", marks(en), marks(hi))
            }
        }
    }

    @Test
    fun `a Hindi text keeps the line breaks, the escaped quotes, the entities and the tags of the English one`() {
        for ((name, hi) in hindi) {
            val en = english[name] ?: continue
            if (hi.plural) continue
            fun count(text: Text, token: String) = Regex(Regex.escape(token)).findAll(text.value).count()
            // English may use the curly quotes where Hindi writes none, but an escaped quote is always one of a pair.
            assertEquals("$name: an escaped quote without its pair", 0, count(hi, "\\\"") % 2)
            for (token in listOf("\\n", "&amp;", "&lt;", "&gt;", "<b>", "</b>", "<i>", "</i>", "…", "⋮", "→")) {
                assertEquals("$name: $token", count(en, token), count(hi, token))
            }
            assertFalse("$name: an apostrophe not escaped", Regex("""(?<!\\)'""").containsMatchIn(hi.value))
        }
    }

    @Test
    fun `Hindi texts are in Devanagari, and the ones that are not are only names, units and codes that English has too`() {
        val devanagari = Regex("[\u0900-\u097F]")
        val latinOnly = hindi.filter { (name, hi) -> hi.translatable && !devanagari.containsMatchIn(hi.value) && hi.value.any { it.isLetter() } }
        for ((name, hi) in latinOnly) {
            assertEquals("$name is Latin only but is not the English text (untranslated, or a name?)", english.getValue(name).value, hi.value)
        }
        assertTrue("Latin-only texts, check they are names or codes: ${latinOnly.keys}", latinOnly.size < 40)
        val devanagariDigits = Regex("[\u0966-\u096F]")
        for ((name, hi) in hindi) assertFalse("$name has Devanagari digits (the numbers are ASCII everywhere)", devanagariDigits.containsMatchIn(hi.value))
    }

    @Test
    fun `no Hindi text is still the Portuguese, Spanish or English sentence`() {
        val shared = Regex("""[A-Za-zÀ-ÿ]{4,}(?:\s+[A-Za-zÀ-ÿ]{3,}){3,}""")
        for ((name, hi) in hindi) {
            if (!hi.translatable) continue
            // Four Latin words in a row only in the lines that quote what Android Auto or Android show in English.
            val runs = shared.findAll(hi.value.replace(Regex("""\\"[^"]*\\""""), "").replace(Regex("""<[^>]+>"""), "")).map { it.value }.toList()
            val holder = name.startsWith("credit_holder_") || name.startsWith("credit_name_")
            assertTrue("$name has a run of Latin words (left in English?): $runs", runs.isEmpty() || holder)
        }
    }

    @Test
    fun `the wind points are the sixteen of the compass in Hindi too`() {
        val extra = File("src/main/res/values-hi/strings_extra.xml").readText()
        val items = Regex("""<string-array name="wind_points">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL).find(extra)!!.groupValues[1]
        assertEquals(16, Regex("<item>").findAll(items).count())
    }

    @Test
    fun `the Hindi files carry the note that no native speaker has read them`() {
        for (file in File("src/main/res/values-hi").listFiles { f -> f.extension == "xml" }.orEmpty()) {
            assertTrue("${file.name}: the note", file.readText().contains("Tradução sem revisão de falante nativo (25/11/2026)."))
        }
    }
}
