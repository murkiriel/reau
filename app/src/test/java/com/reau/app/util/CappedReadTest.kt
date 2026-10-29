package com.reau.app.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weather's answer was read to the end whatever its size; both downloads now stop at a ceiling. */
class CappedReadTest {
    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test(timeout = 10_000) // a reader that does not stop must fail the test, not hang the run
    fun `an answer under the ceiling is read whole`() {
        val data = bytes(20_000)
        val r = CappedRead.read(ByteArrayInputStream(data), 50_000)
        assertArrayEquals(data, r.bytes)
        assertFalse(r.truncated)
    }

    @Test(timeout = 10_000) // a reader that does not stop must fail the test, not hang the run
    fun `an answer of exactly the ceiling is whole too, one byte more is cut and said so`() {
        val exact = CappedRead.read(ByteArrayInputStream(bytes(10_000)), 10_000)
        assertEquals(10_000, exact.bytes.size)
        assertFalse(exact.truncated)

        val over = CappedRead.read(ByteArrayInputStream(bytes(10_001)), 10_000)
        assertEquals(10_000, over.bytes.size)
        assertTrue(over.truncated)
    }

    @Test(timeout = 10_000) // a reader that does not stop must fail the test, not hang the run
    fun `a stream that never ends costs the ceiling and no more`() {
        val endless = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int { served++; return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 7); served += len; return len }
        }
        val r = CappedRead.read(endless, 100_000)
        assertEquals(100_000, r.bytes.size)
        assertTrue(r.truncated)
        assertTrue("read ${endless.served} bytes", endless.served <= 100_001)
    }

    @Test(timeout = 10_000) // a reader that does not stop must fail the test, not hang the run
    fun `an empty answer is empty`() {
        val r = CappedRead.read(ByteArrayInputStream(ByteArray(0)), 10)
        assertEquals(0, r.bytes.size)
        assertFalse(r.truncated)
    }

    @Test(timeout = 10_000)
    fun `a stream that gives one byte at a time is read up to the ceiling all the same`() {
        val data = bytes(300)
        val slow = object : java.io.InputStream() {
            var at = 0
            override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val one = read()
                if (one < 0) return -1
                b[off] = one.toByte()
                return 1
            }
        }
        val r = CappedRead.read(slow, 200)
        assertEquals(200, r.bytes.size)
        assertTrue(r.truncated)
        assertArrayEquals(data.copyOf(200), r.bytes)
    }
}
