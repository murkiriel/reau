package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rule of the project (the owner's, 27/10/2026): every document that exists in English and in Portuguese also exists in Spanish. The
 * Markdown ones are `NAME.md` and `NAME.pt-br.md` at the root of the repository, each with a `NAME.es.md`; the site has docs/en and docs/pt,
 * each page with its docs/es twin. The twins have the same shape (the same headings and entries), and each English and Portuguese document
 * links to the Spanish one, so a reader can get there.
 */
class SpanishDocsTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }

    private val markdownPairs: List<String> = root.listFiles { f -> f.isFile && f.name.endsWith(".pt-br.md") }.orEmpty()
        .map { it.name.removeSuffix(".pt-br.md") }.filter { File(root, "$it.md").isFile }.sorted()

    private fun lines(file: File, prefix: String) = file.readLines().count { it.startsWith(prefix) }

    @Test
    fun `the project has the documents the rule is about`() {
        assertTrue("$markdownPairs", markdownPairs.containsAll(listOf("README", "CHANGELOG", "CONTRIBUTING", "TERMS_OF_USE")))
    }

    @Test
    fun `every Markdown document in English and Portuguese has a Spanish one`() {
        for (name in markdownPairs) assertTrue("$name.es.md is missing", File(root, "$name.es.md").isFile)
    }

    @Test
    fun `every site page in English and Portuguese has a Spanish one`() {
        val pages = File(root, "docs/en").list { _, n -> n.endsWith(".html") }.orEmpty().toSet() intersect
            File(root, "docs/pt").list { _, n -> n.endsWith(".html") }.orEmpty().toSet()
        assertTrue("$pages", pages.size >= 4)
        for (page in pages) assertTrue("docs/es/$page is missing", File(root, "docs/es/$page").isFile)
    }

    @Test
    fun `the Spanish documents have the same sections and entries as the English ones`() {
        for (name in markdownPairs) {
            val en = File(root, "$name.md")
            val es = File(root, "$name.es.md")
            for (prefix in listOf("# ", "## ", "### ", "- ")) {
                assertEquals("$name.es.md: lines starting with '$prefix'", lines(en, prefix), lines(es, prefix))
            }
        }
    }

    @Test
    fun `the Spanish site pages have the same tags as the English ones`() {
        val tag = Regex("<([a-zA-Z][a-zA-Z0-9]*)")
        fun counts(file: File) = tag.findAll(file.readText()).groupingBy { it.groupValues[1].lowercase() }.eachCount()
        for (page in File(root, "docs/en").list { _, n -> n.endsWith(".html") }.orEmpty()) {
            // The language switcher of the Spanish pages is the same as the English ones'; the only tags in which they may differ are none.
            assertEquals("docs/es/$page", counts(File(root, "docs/en/$page")), counts(File(root, "docs/es/$page")))
        }
    }

    @Test
    fun `each English and Portuguese document links to the Spanish one`() {
        // The terms of use are the exception: the root files are the texts the app shows, word for word (TermsTest), so they link nothing.
        for (name in markdownPairs.filter { it != "TERMS_OF_USE" }) {
            for (file in listOf("$name.md", "$name.pt-br.md")) {
                assertTrue("$file does not link $name.es.md", "$name.es.md" in File(root, file).readText())
            }
        }
        for (language in listOf("en", "pt")) {
            for (page in File(root, "docs/$language").list { _, n -> n.endsWith(".html") }.orEmpty()) {
                assertTrue("docs/$language/$page does not link its Spanish page", "../es/$page" in File(root, "docs/$language/$page").readText())
            }
        }
    }

    @Test
    fun `the sitemap lists the Spanish pages`() {
        val sitemap = File(root, "docs/sitemap.xml").readText()
        for (page in File(root, "docs/es").list { _, n -> n.endsWith(".html") }.orEmpty()) {
            assertTrue("$page is not in the sitemap", "/reau/es/${if (page == "index.html") "" else page}" in sitemap)
        }
    }
}
