package com.reau.app.ui

/**
 * The text of a log file cut into the items of the viewer's lazy list ([LogViewerScreen]): drawing one 256 KB text measures and draws all
 * of it at once, a lazy list draws only the items that are on the screen. An item is up to [LINES_PER_ITEM] lines (the screen reader goes
 * a few dozen lines at a time, not one by one), and a line longer than [MAX_LINE_CHARS] is cut into pieces of that size, so that no item is a
 * huge text. Pure, so the JVM tests cover it; the screen calls it off the main thread.
 */
object LogLines {
    /** Lines in an item: the log's lines are about 150 characters, so an item is about 3 KB of text. */
    const val LINES_PER_ITEM = 20

    /** A line longer than this is shown in pieces of this size (a line of a dump can be the size of the whole file). */
    const val MAX_LINE_CHARS = 4_000

    /**
     * [text] as list items, in order, the lines of an item joined by `\n`. `\r\n`, `\n` and a lone `\r` end a line; the end of the text is not
     * a line of its own (a file ending in a newline has no empty last line), and an empty text has no items. An empty line in the middle stays.
     */
    fun items(text: String, linesPerItem: Int = LINES_PER_ITEM, maxLineChars: Int = MAX_LINE_CHARS): List<String> {
        require(linesPerItem > 0 && maxLineChars > 0)
        val items = ArrayList<String>(text.length / (linesPerItem * AVERAGE_LINE_CHARS) + 1)
        val item = StringBuilder()
        var inItem = 0

        fun add(piece: CharSequence) {
            if (inItem > 0) item.append('\n')
            item.append(piece)
            if (++inItem == linesPerItem) {
                items += item.toString()
                item.setLength(0)
                inItem = 0
            }
        }

        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                addLine(text, start, i, maxLineChars, ::add)
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) addLine(text, start, text.length, maxLineChars, ::add)
        if (inItem > 0) items += item.toString()
        return items
    }

    private fun addLine(text: String, from: Int, to: Int, maxLineChars: Int, add: (CharSequence) -> Unit) {
        if (to - from <= maxLineChars) {
            add(text.subSequence(from, to))
            return
        }
        var at = from
        while (at < to) {
            var end = minOf(at + maxLineChars, to)
            // Never between the two halves of one character (an emoji is two chars).
            if (end < to && end - at > 1 && text[end - 1].isHighSurrogate()) end--
            add(text.subSequence(at, end))
            at = end
        }
    }

    private const val AVERAGE_LINE_CHARS = 150
}
