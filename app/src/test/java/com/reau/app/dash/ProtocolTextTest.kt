package com.reau.app.dash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app in Hindi (25/11/2026): the fixed words REau writes in the dash's own text fields (the title of the gold band, the call card's name, the
 * info ring's pages in words) go in English, because nobody knows yet whether the dash has a Devanagari font; what REau draws on the video (the ring,
 * the joystick's legend, the volume and call cards, the badges) keeps the app's language, as Android renders that. Read from the code: which text goes
 * by which door is what these tests hold.
 */
class ProtocolTextTest {
    private val root = File("src/main/java/com/reau/app")
    private fun code(path: String) = File(root, path).readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `with the app in Hindi the fixed words of the dash's text fields are English, in any other language they are the app's`() {
        for (tag in listOf("hi", "hi-IN", "HI", "hi_IN", "Hi-in")) assertTrue(tag, ProtocolText.inEnglish(tag))
        for (tag in listOf("en", "pt-BR", "pt", "es", "es-419", "fr", "")) assertFalse(tag, ProtocolText.inEnglish(tag))
        assertEquals("only Hindi, until the owner tests the font", setOf("hi"), ProtocolText.ENGLISH_FOR)
    }

    @Test
    fun `what goes in a text field of the dash is read with the English words`() {
        val sides = code("service/LiveSides.kt")
        // The title of the gold band: the tap's name and the volume level, and the caller's card (the name when there is none).
        assertTrue(sides, "ActionEchoText.text(ProtocolText.context(words), label)" in sides)
        assertTrue(sides, "{ ActionEchoText.volume(ProtocolText.context(words), change) }" in sides)
        assertTrue(sides, "session.showCallerCard(caller, ProtocolText.context(context).getString(R.string.caller_private))" in sides)
        // The title's pages of the info ring.
        val feed = code("service/InfoRingFeed.kt")
        assertTrue(feed, "val fieldWords = ProtocolText.context(context)" in feed)
        assertTrue(feed, "val title = titleWords(fieldWords)" in feed)
        assertTrue(feed, "words = words(fieldWords)," in feed)
    }

    @Test
    fun `what is drawn on the video keeps the app's language`() {
        val sides = code("service/LiveSides.kt")
        assertTrue(sides, "ActionLegend.card(map.legend(mode)) { com.reau.app.dash.ActionEchoText.text(words, it) }" in sides)
        assertTrue(sides, "ActionEchoText.volumeCard(appContext, step.card)" in sides)
        assertTrue(sides, "ActionEchoText.callWords(appContext)" in sides)
        assertTrue(sides, "callCard.heard(caller, context.getString(R.string.caller_private))" in sides)
        val feed = code("service/InfoRingFeed.kt")
        assertEquals("the ring's frame reads the words of the app's context, once", 1, Regex("""words = words\(context\),""").findAll(feed).count())
        // The compositor and the painters take no ProtocolText: only the places above do.
        val users = File(root, "").walkTopDown().filter { it.isFile && it.extension == "kt" && "ProtocolText" in it.readText() }.map { it.name }.sorted().toList()
        assertEquals(users.toString(), listOf("InfoRingFeed.kt", "LiveSides.kt", "ProtocolText.kt"), users)
    }

    @Test
    fun `the Hindi texts that go in a field exist anyway, so that taking the language out of the set is the only change`() {
        for (name in listOf("caller_private", "volume_echo_level", "volume_echo_call", "title_rain_now", "title_battery", "echo_confirm")) {
            assertTrue(name, Regex("""name="$name"""").containsMatchIn(File("src/main/res").walkTopDown().filter { "values-hi" in it.path && it.extension == "xml" }.joinToString("") { it.readText() }))
        }
    }
}
