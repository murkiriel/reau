package com.reau.app.devtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No Devanagari letter in the Kotlin of the app, comments and KDoc included (review of 26/11/2026, D2: a KDoc example in the main source had them). The street-name test of
 * the dash never exists in a release (StreetTrialAbsenceInReleaseTest reads the CODE of main and release); this one reads every line, so a letter in a comment, a string or
 * a sample cannot slip in either. The texts the rider reads are in the resources (values-hi), not in Kotlin; a sample in a comment is written with `\uXXXX` escapes.
 */
class NoDevanagariInKotlinTest {
    private val root = File("src")
    private val devanagari = Regex("[ऀ-ॿ]")

    private fun kotlin(dir: String): List<File> = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no Kotlin file of main or release has a Devanagari letter, not even in a comment`() {
        val files = kotlin("main") + kotlin("release")
        assertTrue("the source folders are found", files.size > 100)
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { devanagari.containsMatchIn(it.value) }.map { "${file.path}:${it.index + 1}" }
        }
        assertEquals("Devanagari letters in Kotlin (write them as \\uXXXX escapes or in the Latin alphabet)", emptyList<String>(), found)
    }

    @Test
    fun `the check sees a letter in a comment and in code, and lets the escapes and the Latin text pass`() {
        assertTrue(devanagari.containsMatchIn(" * \"José चौक\" in a KDoc"))
        assertTrue(devanagari.containsMatchIn("val s = \"क\""))
        assertEquals(false, devanagari.containsMatchIn(" * \"José \\u091A\\u094C\\u0915\" written with escapes, or \"José cauka\""))
    }
}
