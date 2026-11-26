package com.reau.app.dash.protocol

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The text written in another script reaches the dash in the Latin alphabet (decision of 25/11/2026, after the test on the dash of 26/11/2026 showed no Devanagari). The JVM
 * has no `android.icu`, so a fake engine stands in for ICU's: what is proved here is WHERE the transliteration is applied (only past Latin-1, run by run, before the cut to
 * Latin-1), that Latin-1 text is not touched, that a failure falls back to the question mark, and the one line per session.
 */
class DashTransliterationTest {
    private val lines = mutableListOf<String>()
    private val asked = mutableListOf<String>()

    /** A stand-in for ICU: the Devanagari word of the street test becomes "chandni", the Cyrillic name of Moscow becomes "Moskva", anything else is not known. */
    private val fake = DashTransliteration.Engine { run ->
        asked += run
        when (run) {
            "\u091A\u093E\u0901\u0926\u0928\u0940" -> "chandni"
            "\u091A\u094C\u0915" -> "chauk"
            "\u041C\u043E\u0441\u043A\u0432\u0430" -> "Moskva"
            else -> null
        }
    }

    @Before
    fun fakeEngine() {
        DashTransliteration.engine = fake
        DashTransliteration.sink = { lines += it }
        DashTransliteration.newSession()
    }

    @After
    fun backToIcu() {
        DashTransliteration.engine = null
        DashTransliteration.sink = { com.reau.app.data.RideDiagnostics.log("dash", it) }
        DashTransliteration.newSession()
    }

    private fun shown(text: String, max: Int = 64) = String(DashCommands.latin1(text, max), Charsets.ISO_8859_1)

    @Test
    fun `a street in Devanagari reaches the dash in the Latin alphabet`() {
        assertEquals("chandni chauk", shown("\u091A\u093E\u0901\u0926\u0928\u0940 \u091A\u094C\u0915"))
    }

    @Test
    fun `Latin-1 text with the accents of Portuguese is the same bytes as before and the engine is never asked`() {
        val text = "Av. S\u00E3o Jo\u00E3o, Concei\u00E7\u00E3o \u00E9 \u00FF \u00C1 \u00F4 \u00FC \u00A0 \u00AE"
        assertEquals(text.toByteArray(Charsets.ISO_8859_1).toList(), DashCommands.latin1(text, 64).toList())
        assertEquals(text, DashCommands.legible(text))
        assertTrue("nothing was asked of the engine: $asked", asked.isEmpty())
        assertTrue("and nothing was told: $lines", lines.isEmpty())
    }

    @Test
    fun `only the run past Latin-1 is transliterated and the accents beside it stay`() {
        assertEquals("Jos\u00E9 Moskva \u00E7", shown("Jos\u00E9 \u041C\u043E\u0441\u043A\u0432\u0430 \u00E7"))
        assertEquals("the engine saw the run alone", listOf("\u041C\u043E\u0441\u043A\u0432\u0430"), asked)
    }

    @Test
    fun `the transliteration comes before the cut to the field, which counts the Latin letters`() {
        assertEquals("chandni chauk".take(8), shown("\u091A\u093E\u0901\u0926\u0928\u0940 \u091A\u094C\u0915", 8))
    }

    @Test
    fun `a run the engine cannot do, or that is not text, is a question mark as it was`() {
        assertEquals("a?b", shown("a\uD83C\uDFB5b"))
        assertEquals("??", shown("\u0645\u0631"))
    }

    @Test
    fun `an engine that throws is the old behaviour, not a crash`() {
        DashTransliteration.engine = DashTransliteration.Engine { throw IllegalStateException("no ICU") }
        assertEquals("??", shown("\u0645\u0631"))
        assertEquals("Jos\u00E9", shown("Jos\u00E9"))
        assertTrue("nothing was transliterated, nothing is told: $lines", lines.isEmpty())
    }

    @Test
    fun `the typographic marks are still ASCII and do not reach the engine`() {
        assertEquals("Don't \"Go\" a-b...", shown("Don\u2019t \u201CGo\u201D a\u2013b\u2026"))
        assertTrue("the marks were mapped before the engine: $asked", asked.isEmpty())
    }

    @Test
    fun `the line goes to the log once per session, without the text, and again after a new session`() {
        shown("\u091A\u094C\u0915")
        shown("\u041C\u043E\u0441\u043A\u0432\u0430")
        assertEquals(listOf(DashTransliteration.LOG_LINE), lines)
        assertEquals("dash text transliterated to Latin for the dash (it has no font for it)", lines.single())
        DashTransliteration.newSession()
        shown("\u091A\u094C\u0915")
        assertEquals(2, lines.size)
    }

    @Test
    fun `a text the engine does not know does not use up the line`() {
        shown("\u0645\u0631")
        assertTrue(lines.isEmpty())
        shown("\u091A\u094C\u0915")
        assertEquals(1, lines.size)
    }

    @Test
    fun `the contact and the song go through the same path`() {
        val song = DashCommands.nowPlaying("\u091A\u094C\u0915", "\u041C\u043E\u0441\u043A\u0432\u0430", "Caf\u00E9")
        val text = String(song, Charsets.ISO_8859_1)
        assertTrue("title, album and artist in Latin: $text", "chauk\u0000Moskva\u0000Caf\u00E9" in text)
    }

    @Test
    fun `the first field of the music card is the song and not the album`() {
        val card = DashCommands.nowPlaying(title = "COUNT UP", album = "Grand Theft Auto VI", artist = "Gunna")
        val fields = String(card, Charsets.ISO_8859_1).substringAfter("COUNT UP").let { "COUNT UP$it" }
        assertTrue("title, album, artist: $fields", fields.startsWith("COUNT UP\u0000Grand Theft Auto VI\u0000Gunna"))
        assertTrue("the album is not before the title", String(card, Charsets.ISO_8859_1).indexOf("COUNT UP") < String(card, Charsets.ISO_8859_1).indexOf("Grand Theft"))
    }

    // \u2500\u2500 wiring, from the source \u2500\u2500

    private fun between(text: String, start: String, end: String): String {
        assertTrue("the anchor is gone: $start", text.contains(start))
        return text.substringAfter(start).substringBefore(end)
    }

    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `legible transliterates before it cuts to Latin-1, and ICU does it with the id that keeps the first run apart`() {
        val legible = between(source("dash/protocol/DashCommands.kt"), "internal fun legible(", "\n    }\n")
        assertTrue(legible, "DashTransliteration.toLatin(" in legible)
        // The typographic marks go first: typographic() is the argument of toLatin(), so it runs before it.
        assertTrue("the typographic marks come before the transliteration", "DashTransliteration.toLatin(typographic(" in legible)
        assertTrue("the cut to Latin-1 comes after it", legible.indexOf("DashTransliteration.toLatin(") < legible.indexOf("cp < 0x100"))
        val file = source("dash/protocol/DashTransliteration.kt")
        assertTrue("Any-Latin; Latin-ASCII" in file)
        assertTrue("the transliterator is made once", "by lazy" in between(file, "private object IcuEngine", "\n    }\n"))
    }

    @Test
    fun `a new session of the dash lets the line be told again`() {
        val session = source("dash/DashSession.kt")
        val reset = between(session, "firstFrame.reset()", "launchReceiveLoop(")
        assertTrue(reset, "DashTransliteration.newSession()" in reset)
    }
}
