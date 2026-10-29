package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class NalSplitterTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun split(data: ByteArray): Pair<List<List<Int>>, Int> {
        val units = mutableListOf<List<Int>>()
        val skipped = NalSplitter.forEachNalUnit(data, 0, data.size) { nal, _ -> units += nal.map { it.toInt() and 0xFF } }
        return units to skipped
    }

    @Test
    fun `units with 3 and 4 byte start codes come out with a 4 byte one`() {
        val (units, skipped) = split(bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 4, 5))
        assertEquals(listOf(listOf(0, 0, 0, 1, 0x67, 1, 2), listOf(0, 0, 0, 1, 0x68, 3, 4, 5)), units)
        assertEquals(0, skipped)
    }

    /** Audit of 20/10/2026: a start code with nothing after it was handed over, and reading its first byte threw. */
    @Test
    fun `a unit with nothing after its start code is skipped and counted, and the ones around it are kept`() {
        val (units, skipped) = split(bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0, 0, 0, 1, 0x68, 9, 9))
        assertEquals(listOf(listOf(0, 0, 0, 1, 0x67, 1, 2), listOf(0, 0, 0, 1, 0x68, 9, 9)), units)
        assertEquals(1, skipped)
    }

    @Test
    fun `an empty unit behind a 3 byte start code is skipped too, and nothing at all gives nothing`() {
        val (units, skipped) = split(bytes(0, 0, 1, 0, 0, 1, 0x65, 7, 7, 7))
        assertEquals(listOf(listOf(0, 0, 0, 1, 0x65, 7, 7, 7)), units)
        assertEquals(1, skipped)
        assertEquals(emptyList<List<Int>>() to 0, split(ByteArray(0)))
        assertEquals(emptyList<List<Int>>() to 0, split(bytes(1, 2, 3, 4, 5)))
    }
}
