package com.reau.app.devtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The street-name test in Devanagari (25/11/2026) never exists in a release: the release stub answers none (no street, no bytes), the main source only
 * reads those two answers, and nothing of the test's name, texts, tags or log line is in the main source, in the release source set or in the main
 * resources.
 */
class StreetTrialAbsenceInReleaseTest {
    private val root = File("src")
    private fun kotlin(dir: String): List<File> = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(file: File) = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")
    private val devanagari = Regex("[ऀ-ॿ]")

    @Test
    fun `the release stub holds no name, no state and no log`() {
        val stub = code(File(root, "release/java/com/reau/app/devtools/ArrowTrial.kt"))
        assertTrue(stub, "fun street(): String? = null" in stub && "fun streetBytes(text: String, max: Int): ByteArray? = null" in stub)
        assertFalse(stub, "DEVANAGARI" in stub || "sendStreet" in stub || "stopStreet" in stub || "\\u09" in stub || devanagari.containsMatchIn(stub))
        assertFalse(stub, "MutableStateFlow" in stub || "RideDiagnostics" in stub || "var " in stub)
    }

    @Test
    fun `nothing of the test's name, tags or log line is in the main source or in the release source set`() {
        for (file in kotlin("main") + kotlin("release")) {
            val body = file.readText()
            assertFalse("${file.name} has the name", "\\u091A\\u093E" in body || "DEVANAGARI_STREET" in body)
            assertFalse("${file.name} has the name in letters", devanagari.containsMatchIn(code(file)))
            assertFalse("${file.name} has the test's tags", "debug_street" in body)
            assertFalse("${file.name} starts or ends the test", "sendStreet" in body || "stopStreet" in body || "STREET_TEST" in body)
            assertFalse("${file.name} says the trigger fired", "street in Devanagari" in body)
        }
    }

    @Test
    fun `the strings of the test are only in the debug resources`() {
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            for (f in File(root, "main/res/$folder").listFiles { x -> x.extension == "xml" }.orEmpty()) {
                assertFalse("${f.path} has debug_street", "debug_street" in f.readText())
            }
            assertTrue("$folder in debug", "debug_street_title" in File(root, "debug/res/$folder/strings.xml").readText())
        }
        assertFalse(File(root, "release/res").exists())
    }

    @Test
    fun `the trigger and its row are only in the debug source`() {
        val debug = File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readText()
        assertTrue("debug_street_send" in debug && "debug_street_stop" in debug && "STREET_TEST" in debug)
        assertEquals("the release DebugTriggers has no row", false, File(root, "release/java/com/reau/app/devtools/DebugTriggers.kt").readText().contains("street"))
    }
}
