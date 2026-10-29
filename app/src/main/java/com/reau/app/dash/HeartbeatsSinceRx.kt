package com.reau.app.dash

/**
 * How many 1 Hz heartbeats the phone has sent since the dash last said anything. The RX watchdog runs inside the heartbeat loop, so
 * when the phone itself stops for a while (Doze, the CPU asleep with the screen off) the loop's next turn finds the dash "silent" for
 * the whole pause though it was the phone that did not listen. A silence only counts against the dash once the phone has been
 * sending all along: at least [DashSession.MIN_HEARTBEATS_BEFORE_SILENT] heartbeats with nothing back. (Another app for this dash
 * found the same: its watchdog needs five heartbeats sent since the last packet.)
 */
internal class HeartbeatsSinceRx {
    private var seenRxMs = 0L
    private var count = 0

    /** One heartbeat turn happened; [lastRxMs] is when the dash last spoke. Returns the heartbeats sent since then, this one included. */
    fun tick(lastRxMs: Long): Int {
        if (lastRxMs != seenRxMs) {
            seenRxMs = lastRxMs
            count = 0
        }
        return ++count
    }
}
