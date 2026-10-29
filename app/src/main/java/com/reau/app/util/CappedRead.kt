package com.reau.app.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most `maxBytes` of an answer from the internet: a server that sends more (a fault, or someone in the way) must not fill the
 * phone's memory. [Capped.truncated] says there was more, and what was read is then cut.
 */
object CappedRead {
    class Capped(val bytes: ByteArray, val truncated: Boolean)

    private const val BUFFER_BYTES = 8_192

    fun read(input: InputStream, maxBytes: Int): Capped {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < maxBytes) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (n < 0) return Capped(out.toByteArray(), truncated = false)
            out.write(buffer, 0, n)
        }
        // Exactly [maxBytes] read: one more byte tells whether the answer ended there or goes on.
        return Capped(out.toByteArray(), truncated = input.read() >= 0)
    }
}
