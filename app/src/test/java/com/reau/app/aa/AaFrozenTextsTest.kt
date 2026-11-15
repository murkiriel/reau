package com.reau.app.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the app says of an Android Auto that accepts the connection and does not answer: with its battery restricted, Android freezes it between
 * sessions, and opening it brings it back. It no longer says it "may be closing a session this REau did not see end".
 */
class AaFrozenTextsTest {
    private val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()

    @Test
    fun `the log says Android Auto may be frozen by Android and that opening it usually clears it`() {
        assertTrue(receiver.contains("Android Auto may be frozen by Android (restricted battery), which usually clears when it is opened"))
        assertFalse("the old sentence is gone", receiver.contains("closing a session this REau did not see end"))
    }

    @Test
    fun `the sister messages of the same round speak of the freeze too`() {
        assertTrue(receiver.contains("it is still closing the previous session, or Android froze it (restricted battery); it clears by itself or when Android Auto is opened"))
        assertTrue("the one when the window runs out", receiver.contains("it is stuck or frozen by Android (restricted battery), not closing"))
    }

    @Test
    fun `the notice of the previous session closing says it too in the three languages, and still never suggests a force stop`() {
        val words = mapOf("values" to ("restricted battery" to "froze"), "values-pt" to ("bateria restrita" to "congelou"), "values-es" to ("batería restringida" to "congeló"))
        for ((folder, pair) in words) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            val text = Regex("""<string name="aa_closing_previous_warning">(.*?)</string>""").find(xml)!!.groupValues[1]
            assertTrue("$folder: $text", pair.first in text && pair.second in text)
        }
    }
}
