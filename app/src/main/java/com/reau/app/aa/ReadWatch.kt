package com.reau.app.aa

/**
 * The one read of Android Auto's screen that is under way on the accessibility service's worker thread, and how long it has been.
 * A read of the windows and nodes of a process that has just died does not answer for about 5 s (the limit of an accessibility call):
 * it froze REau's own screen for 5.03 s in three of nine stops (`Skipped 301 frames`, `Davey! 5033 ms`, 19 and 20/10/2026). The reads
 * now run off the main thread, and a read that does not answer in [limitMs] is taken as Android Auto being gone. Each read has a
 * number, so the late answer of one given up on is not taken for the answer of the next. The limit counts from the moment the worker takes
 * the read up ([begin]), not from the post: the worker also does the clicks and the re-reads of the screen, and a read that waits behind
 * them is not Android Auto being gone (only a wait of [queueLimitMs] is). Not thread-safe: the main thread drives it.
 */
class ReadWatch(private val limitMs: Long = LIMIT_MS, private val queueLimitMs: Long = QUEUE_LIMIT_MS) {
    private var number = 0
    private var since = -1L
    private var begun = -1L

    /** Whether a read is under way. */
    fun inFlight(): Boolean = since >= 0

    /** A read begins at [nowMs]; its number. */
    fun start(nowMs: Long): Int {
        since = nowMs
        begun = -1
        return ++number
    }

    /** The worker took the read [id] up at [nowMs]: the limit counts from here. */
    fun begin(id: Int, nowMs: Long) {
        if (id == number && since >= 0) begun = nowMs
    }

    /** The read [id] answered: true if it is the one under way (a late answer of one given up on is not). */
    fun finish(id: Int): Boolean {
        if (id != number || since < 0) return false
        since = -1
        return true
    }

    /** The read under way has gone [limitMs] without an answer since the worker took it up (or [queueLimitMs] without it being taken up). */
    fun overdue(nowMs: Long): Boolean = since >= 0 && if (begun >= 0) nowMs - begun > limitMs else nowMs - since > queueLimitMs

    /** The walk ended or the service went: nothing is under way, and the answer of a read still out is ignored. */
    fun reset() {
        since = -1
        begun = -1
        number++
    }

    companion object {
        /** Under the 5 s of an accessibility call, over a slow phone's read of 600 nodes (a binder call each). */
        const val LIMIT_MS = 3_000L

        /** The most a read may wait behind the worker's other work (each call of it can take 5 s on a dead process). */
        const val QUEUE_LIMIT_MS = 15_000L
    }
}
