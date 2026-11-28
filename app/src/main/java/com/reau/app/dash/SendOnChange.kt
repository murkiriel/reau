package com.reau.app.dash

import com.reau.app.util.MonotonicClock

/**
 * "Send to the dash only what changed" ([com.reau.app.settings.REauSettings.sendOnChange]), as measured on the dash: the video frames are
 * the one periodic send during a projection and keep it alive; everything else is a value the dash HOLDS until it is replaced (or, for a visibility
 * latch, until the latch goes off), with no inactivity timeout. So a command is sent the first time of a session, again when its content changes,
 * and again after anything that makes the dash forget (a new link, the dash's own exit from the navigation), and not once a second.
 *
 * A slow reaffirmation besides ([refreshMs], 30 s): a value held as sent that long goes again at its next ask though it did not change. A datagram that reached the
 * phone's radio may still be lost on the way or inside the dash (measured in the dash's behaviour: under a peak of load it can stop taking packets for a moment), and a
 * value said only once would then stay wrong until it changed, or a latch (05 56) never arrive. Each key counts its own time from its own last send, so the
 * reaffirmations do not come in one clump; with the 1 Hz loops that ask, nothing goes more often than once every [refreshMs] when it does not change.
 *
 * Pure bookkeeping, one object per [DashSession]. Each command has a [key] and a [content] (the bytes that say what the dash would show: the packet
 * itself, or only the part of it that matters, as the time's hour and minute without the seconds, or a call's state and name without the random
 * IV of its encryption). Two threads may ask at once (the loops, the joystick's answers, a volume step), so every step is under one lock.
 *
 * Off ([enabled] false), [offer] always says yes and nothing is kept: the session sends exactly what it sent before this existed.
 */
internal class SendOnChange(
    enabled: Boolean = false,
    /** How long a value is held as sent before it goes again unchanged; 0 or less: never (only a change, or what makes the dash forget, sends it again). */
    private val refreshMs: Long = REFRESH_MS,
    private val nowMs: () -> Long = MonotonicClock::nowMs,
) {
    /** What went of a key and when ([nowMs]). */
    private class Held(val content: ByteArray, val atMs: Long)

    private val lock = Any()
    private val last = HashMap<String, Held>()

    /** Whether commands are held back when nothing changed; set at the start of each session ([begin]). */
    @Volatile var enabled: Boolean = enabled
        private set

    /** A new session (or a new link of the same one): the dash has nothing, so every command is the first again; [on] is the rider's switch as it is now. */
    fun begin(on: Boolean) = synchronized(lock) {
        enabled = on
        last.clear()
    }

    /** Everything is to be sent again at the next ask (the dash was told something here that the commands do not know, or has just started to show). */
    fun reset() = synchronized(lock) { last.clear() }

    /**
     * Whether [key] with [content] is to be sent now: never sent in this session, its content changed since the last one that went, or that one went [refreshMs]
     * ago or more. Always, when off.
     */
    fun offer(key: String, content: ByteArray): Boolean = synchronized(lock) {
        if (!enabled) return true
        val before = last[key] ?: return true
        !before.content.contentEquals(content) || due(before)
    }

    /** Whether [held] went long enough ago to be said again unchanged. */
    private fun due(held: Held): Boolean = refreshMs > 0L && nowMs() - held.atMs >= refreshMs

    /** [content] of [key] reached the wire: the dash has it now (and its [refreshMs] count from now). Nothing is kept when off. */
    fun sent(key: String, content: ByteArray) = synchronized(lock) {
        if (enabled) last[key] = Held(content.copyOf(), nowMs())
    }

    /** [key] is to be sent at the next ask whatever it says (the dash lost it, or the thing it carries ended). */
    fun forget(key: String) {
        synchronized(lock) { last.remove(key) }
    }

    /** Whether [key] is held as sent (a test and the log read it). */
    fun holds(key: String): Boolean = synchronized(lock) { key in last }

    /**
     * Asks, sends and remembers in one: [send] runs when [offer] says yes and its answer says whether the packet reached the wire (a failed or held-back send is
     * not remembered, so the next ask tries again). True when [send] was asked to run, whatever it answered, so the caller counts what it attempted as before.
     */
    inline fun attempt(key: String, content: ByteArray, send: () -> Boolean): Boolean {
        if (!offer(key, content)) return false
        if (send()) sent(key, content)
        return true
    }

    /**
     * Whether the card put at the end of the batch of a button's answers ([batchSize] packets, of which [answersSize] were the answers) reached the wire: it was in
     * the batch and the whole batch went ([allWired]).
     */
    fun cardWent(batchSize: Int, answersSize: Int, allWired: Boolean): Boolean = batchSize > answersSize && allWired

    companion object {
        /** The slow reaffirmation of what did not change (28/11/2026): every 30 s, as the time used to go; never anything at 1 Hz. */
        const val REFRESH_MS = 30_000L

        /** The session's commands that are held by their key. The music card's four packets use the key of their first TLV (05 17, 05 0D, 05 58, 05 19). */
        const val PROJECTION = "0556"
        const val STATUS = "status"
        const val CLOCK = "0606"
        const val ROUTE_CARD = "route-card"
        const val NAV = "nav"
        const val CALL = "call"

        /** The key of a music packet whose first TLV cannot be read (never, for the packets [DashCommands] builds). */
        const val MUSIC = "music"

        /** The music card's keys other than its visibility latch (05 17): forgotten when the card goes away, so the next song shows whole. */
        val MUSIC_BODY = listOf("050D", "0558", "0519")
    }
}
