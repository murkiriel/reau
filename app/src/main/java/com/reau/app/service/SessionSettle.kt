package com.reau.app.service

/**
 * A setting that needs a new Android Auto session
 * (resolution, density, margins) waits [waitMs] with no other such change before the session is opened, with the last
 * values. Without the wait, four density changes within a second were seen to open three sessions, each a
 * reconnection of Android Auto. The fit, the crop and the move do not come here: they still apply at once.
 *
 * [later] runs an action after a delay and hands back how to cancel it (a coroutine in the sides, a clock in tests).
 */
class SessionSettle(private val waitMs: Long? = null, private val later: (Long, () -> Unit) -> () -> Unit) {
    private var pending: (() -> Unit)? = null

    /** [action] runs [waitMs] from now, unless another offer comes first: then only the newest runs. */
    @Synchronized
    fun offer(action: () -> Unit) {
        pending?.invoke()
        pending = later(waitMs ?: inUseMs) {
            synchronized(this) { pending = null }
            action()
        }
    }

    companion object {
        const val WAIT_MS = 1_000L

        /**
         * The wait in use: none, in every build (the rider's choice of 28/10/2026 after the ride of the fastest follow of a setting: each
         * change opens its session at once, so two quick taps may open two). [WAIT_MS] is what a settle made on its own waits.
         */
        const val IN_USE_MS: Long = 0L

        /**
         * The wait a [SessionSettle] made without its own takes, read at each offer: [IN_USE_MS] unless a device test
         * sets it, to run the release wait in the debug build (the settle's end-to-end test needs the real second).
         */
        @Volatile
        var inUseMs: Long = IN_USE_MS
    }
}
