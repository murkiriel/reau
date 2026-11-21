package com.reau.app.service

/**
 * The one place the running video loop is kept ([LiveSides]), with the rule of who may put one there. Three callers reach it from different threads:
 * [start] and [stop] come through the [ProjectionController]'s queue (the panel's session opening and closing), and [replace] comes from
 * the live settings (a new pace, `ACTION_REFIT`), which the queue does not serialize. Without the rule, a panel that dropped at the very moment the
 * pace was changed could leave a loop recreated over a session with no panel, and the next [start] overwrote it without stopping it: two
 * loops on one compositor and an encoder left running until the projection was turned off.
 *
 * The rule: the slot is only changed under [lock], and every loop that leaves it is stopped by the one that took it out, outside the lock (a stop waits for the
 * poller and a start builds an encoder: neither is done holding it). [epoch] counts the starts and the stops: a [replace] that was building its
 * loop while the session ended (or another began) finds the epoch changed and stops what it built instead of putting it in.
 * [stopLoop] is what stops a loop, so a JVM test can count what was stopped.
 */
internal class LoopSlot<L : Any>(private val stopLoop: (L) -> Unit) {
    private val lock = Any()
    private var current: L? = null
    private var epoch = 0L

    /** The loop in the slot now, for a test and the logs. */
    val loop: L? get() = synchronized(lock) { current }

    /**
     * The panel's session began: a loop left over is stopped first (there is never more than one), then [make] builds the new one, which may fail (null: nothing
     * runs). The loop built is the session's, and is put in the slot, stopping any that got there meanwhile.
     */
    fun start(make: () -> L?) {
        val left = synchronized(lock) {
            epoch++
            current.also { current = null }
        }
        left?.let(stopLoop)
        val fresh = make() ?: return
        val raced = synchronized(lock) { current.also { current = fresh } }
        raced?.let(stopLoop)
    }

    /** The panel's session ended: the loop in the slot is stopped, and a [replace] that was building one will not put it in. */
    fun stop() {
        val left = synchronized(lock) {
            epoch++
            current.also { current = null }
        }
        left?.let(stopLoop)
    }

    /**
     * A new pace: the loop in the slot is stopped and [make] builds another for the new settings. Only while there is a loop (the panel's session is up and
     * streaming): with none, nothing is built. A loop built while the session ended or another began is stopped, not kept. False when nothing was put in the slot.
     */
    fun replace(make: () -> L?): Boolean {
        val (running, mine) = synchronized(lock) {
            val old = current ?: return false
            current = null
            old to ++epoch
        }
        stopLoop(running)
        val fresh = make() ?: return false
        val kept = synchronized(lock) {
            (epoch == mine && current == null).also { if (it) current = fresh }
        }
        if (!kept) stopLoop(fresh)
        return kept
    }
}
