package com.reau.app.dash

import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The short names of the joystick's taps (P30 Eco), one place for the echo in the dash's title and the legend on its video: all of them in the
 * three languages, within what the dash's street field holds and in the letters it reads (Latin-1), and one for each action the page offers.
 */
class ActionEchoTest {
    private val languages = mapOf("en" to "values", "pt" to "values-pt", "es" to "values-es")

    private fun strings(folder: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    /** The name of the string a label points to, from the generated R class. */
    private fun nameOf(label: EchoLabel): String {
        val fields = Class.forName("com.reau.app.R\$string").fields
        return fields.first { it.getInt(null) == label.res }.name
    }

    @Test
    fun `a label is at most as long as the dash's street field holds`() {
        assertEquals(DashCommands.ROAD_MAX, ActionEcho.LABEL_MAX)
        assertEquals(19, ActionEcho.LABEL_MAX)
    }

    @Test
    fun `every label has a text in each language, short enough for the title`() {
        for ((language, folder) in languages) {
            val texts = strings(folder)
            for (label in EchoLabel.entries) {
                val text = texts[nameOf(label)]
                assertNotNull("$language: no text for $label", text)
                assertTrue("$language $label is empty", text!!.isNotBlank())
                assertTrue("$language $label '$text' has ${text.length} characters, the title holds ${ActionEcho.LABEL_MAX}", text.length <= ActionEcho.LABEL_MAX)
                assertEquals("$language $label: no space at the ends", text.trim(), text)
            }
        }
    }

    @Test
    fun `every label reaches the dash whole, each letter as its one Latin-1 byte`() {
        for ((language, folder) in languages) {
            val texts = strings(folder)
            for (label in EchoLabel.entries) {
                val text = texts.getValue(nameOf(label))
                assertEquals("$language $label: the dash would change a letter", text, DashCommands.legible(text))
                val bytes = DashCommands.latin1(text, ActionEcho.LABEL_MAX)
                assertEquals("$language $label: one byte a letter, none cut", text.length, bytes.size)
                assertEquals("$language $label: the dash reads back the same", text, String(bytes, Charsets.ISO_8859_1))
                assertTrue("$language $label: no question mark", '?' !in text)
            }
        }
    }

    @Test
    fun `the labels read differently from one another in a language, so the rider can tell them apart`() {
        for ((language, folder) in languages) {
            val texts = strings(folder)
            val said = EchoLabel.entries.map { texts.getValue(nameOf(it)) }
            assertEquals("$language: a name twice", said.distinct().size, said.size)
        }
    }

    @Test
    fun `the labels are the only echo texts, each named echo and each used`() {
        val names = EchoLabel.entries.map(::nameOf)
        assertEquals(names.distinct(), names)
        for (folder in languages.values) {
            assertEquals("$folder: an echo text with no label", names.sorted(), strings(folder).keys.filter { it.startsWith("echo_") }.sorted())
        }
    }

    @Test
    fun `every action of the page has a label but nothing`() {
        assertNull(EchoLabel.of(Action.NOTHING))
        for (action in Action.entries.filter { it != Action.NOTHING }) assertNotNull("$action", EchoLabel.of(action))
        val labels = Action.entries.mapNotNull { EchoLabel.of(it) }
        assertEquals("two actions with one name", labels.distinct(), labels)
    }

    @Test
    fun `the echo shows for a second and a half and the legend for three seconds`() {
        assertEquals(1_500L, ActionEcho.SHOW_MS)
        assertEquals(3_000L, ActionLegend.SHOW_MS)
    }

    @Test
    fun `a legend card is made of the labels in the language asked and a dash for what does nothing`() {
        val card = ActionLegend.card(
            listOf(
                Control.UP to null,
                Control.DOWN to EchoLabel.HOME,
            ),
        ) { "<${it.name}>" }
        assertEquals(
            LegendCard(
                listOf(
                    LegendRow(Control.UP, null),
                    LegendRow(Control.DOWN, "<HOME>"),
                ),
            ),
            card,
        )
    }
}
