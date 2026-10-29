package com.reau.app.aa

/**
 * Counts the messages in a row that the TLS engine could not decrypt. One bad record is dropped and the session goes on, but
 * when every record fails (the engine closed, or out of step with Android Auto) the read loop kept reading, the session
 * looked active and no picture came, for up to 15 s until the header timeout ended it (audit of 20/10/2026). A few in a row
 * end the session at once, and the receiver dials again.
 */
internal class DecryptFailures(private val limit: Int = LIMIT) {
    private var streak = 0

    /** A message decrypted: the count starts over. */
    fun ok() {
        streak = 0
    }

    /** A message failed to decrypt; true when this makes [limit] in a row. */
    fun failed(): Boolean = ++streak >= limit

    companion object {
        const val LIMIT = 3
    }
}
