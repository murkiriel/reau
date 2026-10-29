package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * A search that runs at most once at a time and not again within [minGapMs] for the same [key]: the later caller gets the
 * answer of the earlier one. The ride log of 20/10/2026 had the scan lookup twice in the same millisecond (the projection
 * and the screen's own search, two managers), each reading the whole scan and writing a line. One process-wide instance
 * serves them all.
 */
class RecentSearch<T>(private val minGapMs: Long = MIN_GAP_MS, private val clock: () -> Long = MonotonicClock::nowMs) {
    private var lastKey: String? = null
    private var lastAtMs = 0L
    private var last: T? = null

    private val lock = Any()

    /**
     * The answer for [key]: the earlier one if it is under [minGapMs] old, else [run]'s. [after] is told the answer of a search that
     * really ran, once the lock is let go (it may be slow, or ask for a search again: it must not hold other callers back).
     */
    fun get(key: String, after: (T) -> Unit = {}, run: () -> T): T {
        val answer = synchronized(lock) {
            val now = clock()
            if (key == lastKey && now - lastAtMs in 0 until minGapMs) {
                @Suppress("UNCHECKED_CAST") // T is what the last run returned (null included)
                return last as T
            }
            run().also {
                lastKey = key
                lastAtMs = clock()
                last = it
            }
        }
        after(answer)
        return answer
    }

    companion object {
        const val MIN_GAP_MS = 1_000L
    }
}

/**
 * Whether the `unavailable … retrying` line is written for the [n]th time of one absence of the dash: the first, then every
 * [UNAVAILABLE_EVERY]th (12 trios of lines in 2m39 of one absence, 20/10/2026). The count goes in the line.
 */
internal fun unavailableLogDue(n: Int): Boolean = n == 1 || (n > 1 && n % UNAVAILABLE_EVERY == 0)

internal const val UNAVAILABLE_EVERY = 5
