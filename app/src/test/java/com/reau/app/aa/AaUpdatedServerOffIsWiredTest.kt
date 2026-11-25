package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule of [AaUpdatedServerOff] is tested in AaUpdatedServerOffTest; where it is kept, read and shown needs a Context, the activity and the card, so those
 * lines are read from the source, as the other "...IsWired" tests do.
 */
class AaUpdatedServerOffIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val res = File("src/main/res")

    private fun String.between(start: String, end: String): String {
        assertTrue("the anchor is gone: $start", contains(start))
        val rest = substringAfter(start)
        assertTrue("the end anchor is gone after '$start': $end", rest.contains(end))
        return rest.substringBefore(end)
    }

    @Test
    fun `an accepted connection keeps the update time of Android Auto`() {
        val serve = File(main, "aa/AaReceiver.kt").readText().between("private fun serve(", "runSession(mine, socket)")
        assertTrue(serve, "AaUpdatedServerOff.markSeenOn(context)" in serve)
    }

    @Test
    fun `the activity asks the rule with the link and what is kept, and logs once when it turns into the case`() {
        val read = File(main, "MainActivity.kt").readText().between("private fun readAaUpdated(", "/** Reads whether the alert channel")
        assertTrue(read, "AaUpdatedServerOff.now(this, link)" in read)
        assertTrue(read, "if (updated && !aaUpdatedServerOff) RideDiagnostics.log(\"aa-server\", com.reau.app.aa.AaUpdatedServerOff.LOG_LINE)" in read)
        val now = File(main, "aa/AaUpdatedServerOff.kt").readText().between("fun now(", "/** What is kept")
        assertTrue(now, "decide(link, Seen.open(context).seenOnAt(), androidAutoUpdatedAt(context))" in now)
    }

    @Test
    fun `the server card adds the update line, with its tag, only in the case`() {
        val card = File(main, "ui/MainParts.kt").readText().between("private fun ServerWarning(", "WarningCard(")
        assertTrue(card, "if (aaUpdated) stringResource(R.string.aa_updated_note) else null" in card)
        assertTrue(card, "updated?.let { it to \"aa_updated_note\" }" in card)
        assertTrue(File(main, "ui/MainParts.kt").readText(), "ServerWarning(state.androidAutoInstalled, state.aaUpdatedServerOff, onOpenAndroidAutoSettings)" in File(main, "ui/MainParts.kt").readText())
        assertTrue(File(main, "MainActivity.kt").readText().contains("aaUpdatedServerOff = aaUpdatedServerOff,"))
    }

    @Test
    fun `the new line is in the three languages, says the update turns the server off and asks to turn it on again`() {
        val words = mapOf("values" to ("updated" to "turns"), "values-pt" to ("atualizado" to "desliga"), "values-es" to ("actualiz" to "apaga"))
        for ((folder, pair) in words) {
            val text = Regex("""<string name="aa_updated_note">(.*)</string>""").find(File(res, "$folder/strings.xml").readText())?.groupValues?.get(1)
            assertTrue("$folder has no aa_updated_note", text != null && text.isNotBlank())
            assertTrue("$folder: $text", pair.first in text!! && pair.second in text)
        }
        assertEquals(3, words.size)
    }
}
