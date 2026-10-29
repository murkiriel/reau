package com.reau.app.aa

/** Splits an Annex B buffer (start codes 00 00 01 or 00 00 00 01) into NAL units. */
internal object NalSplitter {
    /**
     * Calls [callback] for each NAL unit in [buffer] from [offset] for [size] bytes, with the unit as a 4-byte start code
     * and its bytes (a 3-byte code is made into 4) and the length of that start code. A unit with nothing after its start
     * code (two start codes in a row) is skipped, not handed over: reading its first byte went past its end and threw once
     * a frame (audit of 20/10/2026). Returns how many were skipped.
     */
    fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit): Int {
        val limit = offset + size
        var skipped = 0
        var start = findStartCode(buffer, offset, limit)
        while (start >= 0) {
            val codeLen = startCodeLength(buffer, start)
            val next = findStartCode(buffer, start + codeLen, limit)
            val end = if (next < 0) limit else next
            val raw = buffer.copyOfRange(start, end)
            val unit = if (codeLen == 3) byteArrayOf(0) + raw else raw
            if (unit.size > HEADER_LEN) callback(unit, HEADER_LEN) else skipped++
            start = next
        }
        return skipped
    }

    /** Where the first start code at or after [from] begins (a code needs a fourth byte to be read), or -1. */
    private fun findStartCode(buffer: ByteArray, from: Int, limit: Int): Int {
        for (i in from until limit - 3) if (startCodeLength(buffer, i) > 0) return i
        return -1
    }

    /** 4 for 00 00 00 01, 3 for 00 00 01 (starting at [i]), else 0. */
    private fun startCodeLength(b: ByteArray, i: Int): Int = when {
        b[i].toInt() != 0 || b[i + 1].toInt() != 0 -> 0
        b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1 -> 4
        b[i + 2].toInt() == 1 -> 3
        else -> 0
    }

    private const val HEADER_LEN = 4
}
