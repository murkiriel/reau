package com.reau.app.dash.protocol

import java.io.File
import kotlinx.coroutines.cancel
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
    private companion object {
        /** "mykhwahm" in Persian, without the zero-width non-joiner the phone puts after the first two letters. */
        const val PERSIAN_CLEAN = "\u0645\u06CC\u062E\u0648\u0627\u0647\u0645"
        const val PERSIAN_WITH_ZWNJ = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645"

        /** An eighth note (U+266A, past Latin-1, a symbol that ICU hands back unchanged). */
        const val NOTE = "\u266A"
    }

    private val realMaker = DashTransliteration.maker
    private val lines = mutableListOf<String>()
    private val asked = mutableListOf<String>()

    /**
     * A STAND-IN for ICU, not what ICU says (the real ICU writes the Hindi words as "camdani cauka", with the accents of the Latin step taken off: see
     * `DashTransliterationDeviceTest`, which uses it): the Devanagari word of the street test becomes "chandni", the Cyrillic name of Moscow becomes "Moskva", a Persian
     * word becomes "mykhwahm", an emoji comes back as it went in (as ICU does), anything else is not known.
     */
    private val fake = DashTransliteration.Engine { run ->
        asked += run
        when (run) {
            "\u091A\u093E\u0901\u0926\u0928\u0940" -> "chandni"
            "\u091A\u094C\u0915" -> "chauk"
            "\u041C\u043E\u0441\u043A\u0432\u0430" -> "Moskva"
            PERSIAN_CLEAN -> "mykhwahm"
            NOTE -> NOTE
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
        DashTransliteration.maker = realMaker
        DashTransliteration.forgetEngine()
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

    @Test
    fun `invisible format characters are dropped before the engine, not turned into question marks`() {
        assertEquals("mykhwahm", shown(PERSIAN_WITH_ZWNJ))
        assertEquals("the engine saw the word without the joiner", listOf(PERSIAN_CLEAN), asked)
        // Direction marks around Latin letters, a zero-width space, a byte-order mark, a word joiner and a variation selector: nothing of them survives, and nothing is asked.
        asked.clear()
        assertEquals("Abc", shown("\u200EAbc\u200F"))
        assertEquals("ab", shown("a\u200Bb\uFEFF\u2060"))
        assertEquals("a?", shown("a\u2764\uFE0F"))
        assertEquals("ab", shown("\u202Aa\u202Cb\u2066\u2069"))
        // Only the heart, a symbol past Latin-1, is asked (the engine hands it back and it becomes "?"); no Latin letter and no invisible mark is.
        assertEquals("only the symbol past Latin-1 reaches the engine: $asked", listOf("\u2764"), asked)
    }

    @Test
    fun `pure Latin-1 with its own invisible character stays byte for byte`() {
        // U+00AD (soft hyphen) is a format character too but it is in Latin-1: the byte is the owner's, not ours to drop.
        val text = "Jo\u00E3o\u00AD \u00A0\u00FF"
        assertEquals(text, DashCommands.legible(text))
        assertEquals(text.toByteArray(Charsets.ISO_8859_1).toList(), DashCommands.latin1(text, 64).toList())
    }

    @Test
    fun `a symbol the engine hands back unchanged is not a transliteration and does not use up the line`() {
        assertEquals("? Lofi", shown("$NOTE Lofi"))
        assertTrue("the engine was asked and gave the same: $asked", NOTE in asked)
        assertTrue("nothing was transliterated, nothing is told: $lines", lines.isEmpty())
        assertEquals("chauk", shown("\u091A\u094C\u0915"))
        assertEquals("the line was still there for the real one", listOf(DashTransliteration.LOG_LINE), lines)
    }

    @Test
    fun `a failure to make the engine is not kept for ever, but tried at most twice a session`() {
        var made = 0
        DashTransliteration.engine = null
        DashTransliteration.forgetEngine()
        DashTransliteration.maker = {
            made++
            null
        }
        repeat(5) { assertEquals("??", shown("\u0645\u0631")) }
        assertEquals("the first try and one more, not five", 2, made)
        DashTransliteration.newSession()
        repeat(5) { shown("\u0645\u0631") }
        assertEquals("a new session may try again, twice", 4, made)
    }

    @Test
    fun `a second try that works is kept and used`() {
        var made = 0
        DashTransliteration.engine = null
        DashTransliteration.forgetEngine()
        DashTransliteration.maker = { if (++made == 1) null else fake }
        assertEquals("??", shown("\u0645\u0631"))
        assertEquals("Moskva", shown("\u041C\u043E\u0441\u043A\u0432\u0430"))
        assertEquals("Moskva", shown("\u041C\u043E\u0441\u043A\u0432\u0430"))
        assertEquals("made twice, then kept", 2, made)
    }

    @Test
    fun `a new session with a scope makes the engine on a pool thread, before any text`() {
        val caller = Thread.currentThread()
        val madeOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val done = java.util.concurrent.CountDownLatch(1)
        DashTransliteration.engine = null
        DashTransliteration.forgetEngine()
        DashTransliteration.maker = {
            madeOn.set(Thread.currentThread())
            done.countDown()
            fake
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            DashTransliteration.newSession(scope)
            assertTrue("the warm-up ran", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("and not on the thread that asked", madeOn.get() !== caller)
        } finally {
            scope.cancel()
        }
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
        val toLatin = between(file, "fun toLatin(raw: String)", "\n    }\n")
        val stripAt = toLatin.indexOf("withoutInvisible(raw)")
        assertTrue("the invisible characters go before the first look at the text: $toLatin", stripAt in 0 until toLatin.indexOf("text.all {"))
        val obtain = between(file, "private fun obtain()", "\n    }\n")
        assertTrue("the transliterator is made once and kept: $obtain", "made ?:" in obtain)
    }

    @Test
    fun `a new session of the dash lets the line be told again`() {
        val session = source("dash/DashSession.kt")
        val reset = between(session, "firstFrame.reset()", "launchReceiveLoop(")
        assertTrue(reset, "DashTransliteration.newSession(scope)" in reset)
    }
}
