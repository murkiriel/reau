package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "answer by the joystick" switch says what the joystick really does. The call goes
 * on a tap of the side (JoystickMap.callFirst acts on the press itself), so the text never speaks of holding. The explanation of the
 * calls lives on the joystick page's "Chamada" tab only: the two other tabs carry no note about it (28/10/2026).
 */
class AnswerCallsTextTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()
    private val text: String = Regex("""<string name="answer_calls">(.*?)</string>""").find(strings)!!.groupValues[1]

    @Test
    fun `the switch speaks of the sides and the centre, never of holding`() {
        assertFalse(text, text.contains("segur", ignoreCase = true))
        // the sides of Android Auto's call card, green on the left
        assertTrue(text, text.contains("a esquerda atende"))
        assertTrue(text, text.contains("a direita ou o centro recusa"))
        // On a call the dash keeps its sides to itself and sends only the middle (0x16), which ends it.
        assertTrue(text, text.contains("o centro desliga"))
    }

    @Test
    fun `the switch still says where it works, that it is on by default and what it needs`() {
        assertTrue(text, text.contains("nos dois modos do painel"))
        assertTrue(text, text.contains("Vem ligada, mas só age depois que as permissões de telefone forem liberadas"))
    }

    @Test
    fun `the map and media tabs have no note about calls, the call tab explains them`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val file = File("src/main/res/$folder/strings.xml").readText()
            assertFalse("$folder still has the note under the buttons", file.contains("name=\"joystick_calls\""))
            assertTrue("$folder keeps the call tab's own text", file.contains("name=\"joystick_call_intro\""))
        }
        val screen = File("src/main/java/com/reau/app/ui/JoystickScreen.kt").readText()
        assertFalse("the note's tag is gone from the screen", screen.contains("joystick_calls"))
    }
}
