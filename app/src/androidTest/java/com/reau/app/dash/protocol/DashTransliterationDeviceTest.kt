package com.reau.app.dash.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transliteration with the REAL ICU of the device (the JVM tests have a stand-in, see `DashTransliterationTest`). What is checked is not a spelling (ICU's rules and the
 * Latin-ASCII step take the accents off, so Hindi comes out as "camdani cauka" and not as the "chandni chauk" a person would write: review of 26/11/2026, C2) but that the
 * result is a non-empty Latin text without a single question mark, which is what the dash can draw. Written 26/11/2026, not run when it was written.
 */
@RunWith(AndroidJUnit4::class)
class DashTransliterationDeviceTest {
    @Before
    fun realIcu() {
        DashTransliteration.engine = null
        DashTransliteration.forgetEngine()
        DashTransliteration.newSession()
    }

    @After
    fun clean() {
        DashTransliteration.forgetEngine()
        DashTransliteration.newSession()
    }

    private fun assertDrawable(what: String, text: String) {
        val shown = DashCommands.legible(text)
        assertTrue("$what came out empty", shown.isNotBlank())
        assertFalse("$what has a question mark: $shown", '?' in shown)
        assertTrue("$what is not Latin-1: $shown", shown.all { it.code < 0x100 })
    }

    @Test
    fun devanagariBecomesNonEmptyAsciiWithoutQuestionMarks() {
        val shown = DashCommands.legible("चाँदनी चौक")
        assertDrawable("Devanagari", "चाँदनी चौक")
        assertTrue("two words: $shown", ' ' in shown.trim())
        assertTrue("ASCII only: $shown", shown.all { it.code < 0x80 })
    }

    @Test
    fun cyrillicAndArabicAndPersianWithInvisibleMarksHaveNoQuestionMarks() {
        assertDrawable("Cyrillic", "Москва")
        assertDrawable("Arabic", "مرحبا")
        // Persian with a zero-width non-joiner inside and a right-to-left mark at the start.
        assertDrawable("Persian with ZWNJ and a direction mark", "‏می‌خواهم")
    }

    @Test
    fun theArrowAndTheFractionOfLatinAsciiAreWrittenInLatinAndNotAsQuestionMarks() {
        // The real ICU writes the arrow as "->" (Latin-ASCII); the JVM test pins "?" because it has no ICU. Here it is what the dash will get.
        assertDrawable("arrow", "Mãe → Terra")
        assertTrue("the accent of the Latin-1 part stays", DashCommands.legible("Mãe → Terra").startsWith("Mãe "))
    }

    @Test
    fun latin1TextIsTheSameAsBefore() {
        val text = "Av. São João, Conceição é ÿ"
        assertEquals(text, DashCommands.legible(text))
    }
}
