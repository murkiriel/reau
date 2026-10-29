package com.reau.app.util

/**
 * Counts failures in a row, for the places that drop one bad item and carry on but must give up when it is every item: one
 * success starts the count over, and [failed] says when the [limit] is reached. Not thread-safe: one loop owns it.
 */
internal class FailureStreak(private val limit: Int) {
    /** How many failures in a row there are now. */
    var count = 0
        private set

    /** An item went well: the count starts over. */
    fun ok() {
        count = 0
    }

    /** An item failed; true when this makes [limit] in a row. */
    fun failed(): Boolean = ++count >= limit
}
