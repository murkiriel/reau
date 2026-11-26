package com.reau.app.dash

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Keeps [GAP_NS] (2 ms) between any two UDP packets REau sends to the dash, the video's RTP and the control channel's commands together: both sockets
 * leave through the same radio, so the gap is one for the two ([DashSocket] owns one pacer and asks it before every send, in [DashSocket.send],
 * [DashSocket.sendBatch] and [DashSocket.sendRtp]). Decided on 25/11/2026 after the riders' logs: some dashes restart
 * 56 to 74 s after REau begins to transmit, and the third-party apps that stay up space their sends 1.5 to 2 ms apart, while REau sent a keyframe's
 * 6 to 17 RTP packets with nothing between them and the routine commands in a clump of 3 or 4 within 35 ms.
 *
 * How it keeps the gap without a lock held while it sends: [reserve] takes the next free slot of one shared timeline, atomically (a compare-and-set
 * on the time of the last slot given): the caller's own `now` if the last slot is [GAP_NS] or more in the past (the first packet after a pause leaves
 * at once), otherwise the last slot plus [GAP_NS]. Two callers can never get slots closer than [GAP_NS], whatever the threads. Each then waits for
 * its own slot ([waitUntil]), alone, and sends: no lock is held while waiting or while the socket send runs, so the video's output thread and the
 * session's coroutines never hold each other up beyond the slots they hold; a command that arrives in the middle of a keyframe takes the next slot,
 * not one after the whole keyframe (the video asks for the slot of each packet just before sending it). The only imprecision is a thread that is
 * descheduled for longer than its slot between the wait and the send: the gap to the previous packet is then still kept, the one to the next
 * may be shorter by what the thread was late; no lock could fix that without holding it across a socket send.
 *
 * The clock and the wait are given ([nanoTime], [waitUntil]) so the tests need no real time. With [enabled] false nothing waits (and nothing is
 * counted but the packets). What it waited is counted for the `dash-stats` line ([windowText]): the total waited and the longest wait of one packet
 * in the window.
 */
class SendPacer(
    val enabled: Boolean = true,
    private val gapNs: Long = GAP_NS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitUntil: (deadlineNs: Long) -> Unit = { parkUntil(it) },
) {
    private val lastSlotNs = AtomicLong(NONE)
    private val windowWaitedNs = AtomicLong()
    private val windowMaxNs = AtomicLong()
    private val totalWaitedNs = AtomicLong()

    /** Before one send: waits what is missing for [gapNs] to have passed since the previous packet's slot; returns the ns it waited (0: it did not). */
    fun pace(): Long {
        if (!enabled) return 0L
        val now = nanoTime()
        val wait = reserve(now) - now
        if (wait > 0L) {
            waitUntil(now + wait)
            windowWaitedNs.addAndGet(wait)
            totalWaitedNs.addAndGet(wait)
            windowMaxNs.accumulateAndGet(wait) { a, b -> maxOf(a, b) }
        }
        return wait
    }

    /** The slot this caller leaves at (>= [nowNs]): the time of the last slot given plus the gap, or [nowNs] when that is already past. */
    internal fun reserve(nowNs: Long): Long {
        while (true) {
            val last = lastSlotNs.get()
            val slot = if (last == NONE) nowNs else maxOf(nowNs, last + gapNs)
            if (lastSlotNs.compareAndSet(last, slot)) return slot
        }
    }

    /** The part of the window's `dash-stats` line, `pace=on waited=12.3ms max=2.0ms` (what it waited in all and the longest wait of one packet), and a new window. */
    fun windowText(): String {
        val waited = windowWaitedNs.getAndSet(0L)
        val max = windowMaxNs.getAndSet(0L)
        return if (enabled) String.format(Locale.US, "pace=on waited=%.1fms max=%.1fms", waited / NS_PER_MS, max / NS_PER_MS) else "pace=off"
    }

    /** The total waited since the pacer was made, in ms, for the totals half of the line. */
    fun totalWaitedMs(): Long = totalWaitedNs.get() / NS_PER_MS.toLong()

    companion object {
        /** The least time between two packets to the dash (the owner's decision of 25/11/2026). */
        const val GAP_NS = 2_000_000L
        private const val NONE = Long.MIN_VALUE
        private const val NS_PER_MS = 1_000_000.0

        /**
         * Parks the thread until [deadlineNs] on the [System.nanoTime] clock. A thread that was interrupted does not wait (and keeps its flag): a
         * stop must not be held up, and a park returns at once while the flag is set, which would spin.
         */
        internal fun parkUntil(deadlineNs: Long) {
            while (!Thread.currentThread().isInterrupted) {
                val left = deadlineNs - System.nanoTime()
                if (left <= 0L) return
                LockSupport.parkNanos(left)
            }
        }
    }
}
