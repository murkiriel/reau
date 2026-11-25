package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log viewer's list items: the text of a file cut into blocks of lines, long lines in pieces (pendency C14). */
class LogLinesTest {
    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun `an empty text has no items`() {
        assertEquals(emptyList<String>(), LogLines.items(""))
    }

    @Test
    fun `a few lines are one item and nothing is lost`() {
        assertEquals(listOf("a\nb\nc"), LogLines.items("a\nb\nc"))
        assertEquals("a file ending in a newline has no empty last line", listOf("a\nb"), LogLines.items("a\nb\n"))
    }

    @Test
    fun `an item has at most the lines per item, and the next one goes on from there`() {
        val items = LogLines.items(lines(45), linesPerItem = 20)
        assertEquals(3, items.size)
        assertEquals(20, items[0].lines().size)
        assertEquals(20, items[1].lines().size)
        assertEquals(5, items[2].lines().size)
        assertEquals("line 21", items[1].lines().first())
        assertEquals("line 45", items.last().lines().last())
        assertEquals("the items joined are the text", lines(45), items.joinToString("\n"))
    }

    @Test
    fun `exactly a whole number of items leaves no empty one`() {
        assertEquals(2, LogLines.items(lines(40), linesPerItem = 20).size)
        assertEquals(3, LogLines.items(lines(41), linesPerItem = 20).size)
    }

    @Test
    fun `CRLF and a lone CR end a line like LF, and CRLF is one end, not two`() {
        assertEquals(listOf("a\nb\nc\nd"), LogLines.items("a\r\nb\rc\nd"))
        assertEquals("a blank line between stays", listOf("a\n\nb"), LogLines.items("a\r\n\r\nb"))
    }

    @Test
    fun `a blank line in the middle stays and a blank text of one newline is one blank line`() {
        assertEquals(listOf("a\n\nb"), LogLines.items("a\n\nb"))
        assertEquals(listOf(""), LogLines.items("\n"))
    }

    @Test
    fun `a long line is cut in pieces of the largest size, each a line of its own`() {
        val items = LogLines.items("x".repeat(25), linesPerItem = 100, maxLineChars = 10)
        assertEquals(listOf("xxxxxxxxxx\nxxxxxxxxxx\nxxxxx"), items)
        assertEquals("a line of exactly the size is not cut", listOf("x".repeat(10)), LogLines.items("x".repeat(10), maxLineChars = 10))
        assertEquals(listOf("x".repeat(10) + "\n" + "x"), LogLines.items("x".repeat(11), maxLineChars = 10))
    }

    @Test
    fun `a long line is never cut between the halves of a character`() {
        val emoji = "😀"
        val items = LogLines.items("a".repeat(9) + emoji + "b", linesPerItem = 10, maxLineChars = 10)
        assertEquals(listOf("a".repeat(9) + "\n" + emoji + "b"), items)
    }

    @Test
    fun `the tail's largest file is some hundreds of items and no item is huge`() {
        val line = "08:00:00.000  [ui] a log line of about the usual size, with numbers 123456 and words"
        val text = generateSequence { line }.take(256 * 1024 / (line.length + 1)).joinToString("\n")
        val items = LogLines.items(text)
        assertTrue("${items.size} items", items.size in 100..200)
        assertTrue(items.all { it.length <= LogLines.LINES_PER_ITEM * (line.length + 1) })
        assertEquals(text, items.joinToString("\n"))
    }

    @Test
    fun `the sizes are the viewer's`() {
        assertEquals(20, LogLines.LINES_PER_ITEM)
        assertEquals(4_000, LogLines.MAX_LINE_CHARS)
    }
}
