package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch of the network server turns the dash off, and a rider did it without knowing. The rule of when to ask is tested in WebSwitchConfirmTest;
 * that the switch goes through it, that Cancelar changes nothing, that the dialog has its two buttons and that the texts say the dash is not used
 * is read from the source and the strings, as the other "...IsWired" tests do.
 */
class WebOnDialogIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val res = File("src/main/res")

    private fun text(folder: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(File(res, "$folder/strings.xml").readText())?.groupValues?.get(1) ?: error("no $name in $folder")

    @Test
    fun `the switch asks through the rule and only a confirmation writes the setting`() {
        val section = File(main, "ui/WebSection.kt").readText()
        val switch = section.substringAfter("SettingSwitch(R.string.web_view_title, R.string.web_view_sub, R.string.web_view_long").substringBefore("} else {")
        assertTrue(switch, "if (WebSwitch.needsConfirmation(it, projectionOn, dashInUse)) asking = true else change(s.copy(webView = it))" in switch)
        val dialog = section.substringAfter("if (asking) {").substringBefore("Column(Modifier.fillMaxWidth().clip(shape)")
        assertTrue(dialog, "onCancel = { asking = false }," in dialog)
        assertTrue(dialog, "asking = false\n                    change(latest.copy(webView = true))" in dialog)
        assertEquals("the setting is written in two places only, the plain path and the confirmation", 2, Regex("""change\((s|latest)\.copy\(webView = """).findAll(section).count())
    }

    @Test
    fun `the question goes when the projection ends, and Ligar writes the settings of that moment and not an old copy`() {
        val section = File(main, "ui/WebSection.kt").readText()
        val head = section.substringAfter("internal fun WebSection(").substringBefore("val shape")
        assertTrue(head, "val latest by rememberUpdatedState(s)" in head)
        assertTrue(head, "LaunchedEffect(projectionOn) { if (!projectionOn) asking = false }" in head)
        val dialog = section.substringAfter("if (asking) {").substringBefore("Column(Modifier.fillMaxWidth().clip(shape)")
        assertTrue("Ligar does not write from the copy of the composition", "s.copy(" !in dialog)
    }

    @Test
    fun `the screen hands the section the projection and the dash state`() {
        val screen = File(main, "ui/ServerScreen.kt").readText()
        assertTrue(screen, "projectionOn = state.projection.phase != Phase.STOPPED, dashInUse = state.projection.dash != DashLink.OFF" in screen)
    }

    @Test
    fun `the dialog has Ligar and Cancelar and says it with its texts`() {
        val dialog = File(main, "ui/WebOnDialog.kt").readText()
        assertTrue(dialog, "onDismissRequest = onCancel," in dialog)
        assertTrue(dialog, "TextButton(onTurnOn, Modifier.heightIn(min = REauSpace.touch).testTag(\"web_on_confirm\")) { Text(stringResource(R.string.action_start)) }" in dialog)
        assertTrue(dialog, "TextButton(onCancel, Modifier.heightIn(min = REauSpace.touch).testTag(\"web_on_cancel\"))" in dialog)
        assertTrue(dialog, "stringResource(R.string.action_cancel)" in dialog)
        assertTrue(dialog, "R.string.web_on_dialog_title" in dialog && "R.string.web_on_dialog_text" in dialog)
        assertEquals("Ligar", text("values-pt", "action_start"))
        assertEquals("Cancelar", text("values-pt", "action_cancel"))
    }

    @Test
    fun `the dialog and the line under the switch say in the four languages that the dash is not used`() {
        val said = mapOf(
            "values" to listOf("dash"),
            "values-pt" to listOf("painel"),
            "values-es" to listOf("tablero"),
            "values-hi" to listOf("डैश"),
        )
        for ((folder, words) in said) {
            for (name in listOf("web_view_sub", "web_on_dialog_title", "web_on_dialog_text")) {
                val body = text(folder, name)
                for (word in words) assertTrue("$folder $name: $body", body.contains(word))
            }
            val body = text(folder, "web_on_dialog_text")
            assertTrue("$folder: the picture goes only to the browser: $body", body.length in 60..220)
            assertTrue("$folder: no apostrophe left unescaped", !Regex("""(?<!\\)'""").containsMatchIn(body))
        }
        assertTrue(text("values-pt", "web_on_dialog_text").endsWith("Ligar mesmo assim?"))
        assertTrue(text("values-pt", "web_on_dialog_text").contains("só no navegador"))
    }

    @Test
    fun `the main screen already says the dash is not used while the server is on`() {
        // Headline.NETWORK_SERVER (the machine) with its notification and its line, both in the strings: nothing to add there.
        assertTrue(text("values-pt", "sub_network_server").contains("painel não é procurado"))
        assertTrue(File(main, "ui/MainLook.kt").readText().contains("Headline.NETWORK_SERVER -> R.string.sub_network_server"))
        assertTrue(File(main, "service/ProjectionMachine.kt").readText().contains("networkServer -> Headline.NETWORK_SERVER"))
    }
}
