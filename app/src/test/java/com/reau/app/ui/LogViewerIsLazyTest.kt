package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log viewer draws the end of a file as a lazy list, not as one text of up to 256 KB (pendency C14). There is no Compose on the JVM here, so the
 * screen's source is what is read; the phone's own test ([LogViewerScreenTest] in androidTest) shows the list and the end.
 */
class LogViewerIsLazyTest {
    private val screen = File("src/main/java/com/reau/app/ui/LogViewerScreen.kt").readText()
    private val fileEnd = screen.substringAfter("private fun FileEnd(").substringBefore("/** How much of the file's start")

    @Test
    fun `the end of the file is a lazy list of the items of LogLines, not one text`() {
        assertTrue(fileEnd, "LazyColumn(state = down" in fileEnd)
        assertTrue("itemsIndexed(items" in fileEnd)
        assertFalse("the whole text is never one Text", "tail.text" in fileEnd.substringAfter("LazyColumn("))
        assertFalse("no scroll of a whole text: the list scrolls itself", "verticalScroll" in fileEnd)
    }

    @Test
    fun `the cut into items is made where the file is read, off the main thread`() {
        val read = fileEnd.substringAfter("withContext(Dispatchers.IO)").substringBefore("val tail =")
        assertTrue(read, "LogLines.items(it.text)" in read)
        assertEquals("only the two reads go off the main thread, as before", 2, Regex("""withContext\(Dispatchers\.IO\)""").findAll(screen).count())
    }

    @Test
    fun `it opens on the newest, and sideways it still never wraps, in the same mono style and colours`() {
        assertTrue("down.scrollToItem(items.lastIndex)" in fileEnd)
        assertTrue("softWrap = false" in fileEnd)
        assertTrue("style = REauMono" in fileEnd.substringAfter("itemsIndexed("))
        assertTrue("MaterialTheme.colorScheme.onSurface" in fileEnd.substringAfter("itemsIndexed("))
        assertTrue("horizontalScroll(rememberScrollState())" in fileEnd)
        assertTrue("the tag the phone's test reads", "testTag(\"log_text\")" in fileEnd)
    }
}
