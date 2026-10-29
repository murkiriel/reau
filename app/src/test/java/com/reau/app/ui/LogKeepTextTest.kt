package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The (i) of "Apagar sozinho os mais velhos que" and the line under it say the ceilings of the build that runs (28/10/2026): 1024 MB of logs
 * and of recordings in a debug build, 200 MB in a release. The ceilings come from the code, the text only has the places for them.
 */
class LogKeepTextTest {
    private fun string(folder: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File("src/main/res/$folder/strings.xml").readText())!!.groupValues[1]

    private fun filled(text: String, logs: Int, recordings: Int) = text.replace("%1\$d", logs.toString()).replace("%2\$d", recordings.toString())

    @Test
    fun `the ceilings of a debug build are 1024 MB and of a release 200 MB, for the logs and for the recordings`() {
        assertEquals(1024 to 1024, logCapsMb(debugBuild = true))
        assertEquals(200 to 200, logCapsMb(debugBuild = false))
    }

    @Test
    fun `the info text and the line say the ceiling of the build, in the three languages, with no fixed 200 MB`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            for (name in listOf("log_keep_days", "log_keep_sub_never")) {
                val text = string(folder, name)
                assertTrue("$folder/$name has a place for the logs' ceiling", "%1\$d" in text && "%2\$d" in text)
                assertFalse("$folder/$name has a fixed 200 MB", "200 MB" in text || "1024 MB" in text)
                val debug = filled(text, logCapsMb(true).first, logCapsMb(true).second)
                val release = filled(text, logCapsMb(false).first, logCapsMb(false).second)
                assertTrue("$folder/$name in a debug build: $debug", "1024 MB" in debug && "200" !in debug)
                assertTrue("$folder/$name in a release: $release", "200 MB" in release && "1024" !in release)
            }
        }
    }

    @Test
    fun `no document says that the 200 MB ceiling is the only one`() {
        for (lang in listOf("en", "pt", "es")) {
            val faq = File("../docs/$lang/faq.html").readText()
            assertFalse("$lang FAQ: a ceiling of recordings of 200 MB, but a release records nothing", Regex("200 MB (of|de) (recordings|gravações|grabaciones)").containsMatchIn(faq))
        }
    }

    /** The "Verificar atualização" block (28/10/2026): the state line is the version alone and the note is two short sentences at most. */
    @Test
    fun `the update note has at most 2 sentences and 40 words in the three languages, and the line before the tap is only the version`() {
        fun words(s: String) = Regex("""\S+""").findAll(s).count()
        fun sentences(s: String) = s.trim().split(Regex("""(?<=[.!?])["”’)]?\s+""")).count { it.isNotBlank() }
        for (folder in listOf("values", "values-pt", "values-es")) {
            val note = string(folder, "update_info")
            assertTrue("$folder: ${sentences(note)} sentences: $note", sentences(note) <= 2)
            assertTrue("$folder: ${words(note)} words: $note", words(note) <= 40)
            assertTrue("$folder: it keeps what GitHub sees and that nothing is installed", "api.github.com" in note)
            val line = string(folder, "update_sub_idle")
            assertTrue("$folder: only the version: $line", words(line) <= 4 && "%1\$s" in line)
        }
    }
}
