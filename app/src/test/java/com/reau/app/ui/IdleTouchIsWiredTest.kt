package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A touch inside a dialog or a sheet restarts the idle count (0.3.98). They are windows of their own, so the activity's onUserInteraction never hears them and the count ran out
 * in the middle of one. Of the text of the source (the windows need an Activity, as in the other "...IsWired" tests); every window of the app is found by a scan, so a new dialog
 * without the modifier fails here.
 */
class IdleTouchIsWiredTest {
    private val root = File("src/main/java/com/reau/app")

    private fun source(path: String) = File(root, path).readText()

    private val windowCall = Regex("""\b(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|Popup|DropdownMenu|DatePickerDialog|TimePickerDialog)\(""")

    private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }

    @Test
    fun `the activity provides the local with the touched of the idle watch`() {
        val main = source("MainActivity.kt")
        assertTrue("com.reau.app.ui.LocalUserTouch provides idleWatch::touched" in main)
        val provider = main.substringAfter("androidx.compose.runtime.CompositionLocalProvider(").substringBefore(") {")
        assertTrue(provider, "LocalUserTouch provides idleWatch::touched" in provider && "LocalREauNight" in provider)
        assertTrue("override fun onUserInteraction() {\n        super.onUserInteraction()\n        idleWatch.touched()" in main)
    }

    @Test
    fun `every dialog and sheet of the app puts the modifier on its card`() {
        val found = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = code(file.readText())
            lines.forEachIndexed { i, line ->
                if (windowCall.containsMatchIn(line) && !line.contains("fun ")) {
                    found += "${file.name}:${i + 1}"
                    // The call's own arguments: from its line to the first line that closes it at the same indent.
                    val indent = line.takeWhile { it == ' ' }.length
                    val end = lines.drop(i + 1).indexOfFirst { it.takeWhile { c -> c == ' ' }.length <= indent && it.isNotBlank() }
                    val call = lines.subList(i, if (end < 0) lines.size else i + 1 + end + 1).joinToString("\n")
                    assertTrue("${file.name}:${i + 1} opens a window of its own without countsAsTouch():\n$call", "countsAsTouch()" in call)
                }
            }
        }
        // The four known today (SettingsDebug, WifiScreen, FitPanel, Sheets); a fifth is found by the scan and has to carry the modifier too.
        assertTrue(found.toString(), found.size >= 4)
        for (name in listOf("SettingsDebug.kt", "WifiScreen.kt", "FitPanel.kt", "Sheets.kt")) assertTrue("$name: $found", found.any { it.startsWith("$name:") })
    }

    @Test
    fun `the modifier reads the finger on the way down, never consumes it, and the default does nothing`() {
        val touch = source("ui/UserTouch.kt")
        val text = code(touch).joinToString("\n")
        assertTrue("val LocalUserTouch = compositionLocalOf<() -> Unit> { {} }" in text)
        assertTrue("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)" in text)
        assertEquals("one touch() call", 1, Regex("""\btouch\(\)""").findAll(text).count())
        assertTrue("the callback is read at each touch, not captured once", "val touch by rememberUpdatedState(LocalUserTouch.current)" in text)
        assertFalse("consumes nothing (the buttons of the card keep working)", Regex("""\bconsume\w*\(""").containsMatchIn(text))
        assertFalse("does not wait for the move or the lift", "awaitPointerEvent" in text || "waitForUpOrCancellation" in text)
    }

    @Test
    fun `the sheet and the dialogs keep their own tags and handlers next to the modifier`() {
        val sheets = source("ui/components/Sheets.kt")
        assertTrue("modifier = Modifier.countsAsTouch().testTag(tag)," in sheets)
        for (file in listOf("ui/SettingsDebug.kt", "ui/WifiScreen.kt", "ui/FitPanel.kt")) {
            val dialog = source(file).substringAfter("    AlertDialog(\n").substringBefore("containerColor")
            assertTrue(file, "onDismissRequest = onCancel," in dialog && "modifier = Modifier.countsAsTouch()," in dialog)
        }
    }
}
