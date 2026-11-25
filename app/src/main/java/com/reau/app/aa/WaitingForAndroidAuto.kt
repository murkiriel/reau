package com.reau.app.aa

/**
 * Whether the dash should say it is waiting for Android Auto. When Android Auto's session drops in the middle of a
 * projection the dash stays connected and the compositor repeats the last picture, until the receiver dials in again
 * (a redial every second; measured, the picture is back in about 1.6 s, 2.2 s at most). A short drop is left as it is:
 * the last picture stays. A drop that passes [afterMs] is not one of those (Android Auto accepted the connection and
 * does not answer, say), and the rider is looking at a map that will not move without knowing why: the dash then says
 * "waiting for Android Auto" until the first new picture arrives.
 *
 * The count begins at the drop (Android Auto's link not [AaLinkState.ACTIVE] while the dash shows the video) and goes
 * on through the redial, even once the link is up again: only a new picture ([videoUnits] changing, [AaVideoMeter]) ends
 * it, and the next drop counts from its own start. With no dash showing the video (not connected, the network server's
 * mode, the projection stopped) there is nobody to tell and the count is let go. [check] runs a few times a second.
 */
class WaitingForAndroidAuto(private val afterMs: Long = AFTER_MS) {
    private var downSinceMs = NOT_DOWN
    private var unitsAtDrop = 0L

    var isWaiting = false
        private set

    /**
     * The change to tell at [nowMs]: [androidAutoUp] is the link's state being ACTIVE, [dashShowing] the dash being connected and
     * given the video, [videoUnits] the pictures Android Auto has sent so far. true = the notice is on now, false = it is off, or null.
     */
    fun check(nowMs: Long, androidAutoUp: Boolean, dashShowing: Boolean, videoUnits: Long): Boolean? {
        if (!dashShowing) {
            downSinceMs = NOT_DOWN
        } else {
            if (downSinceMs != NOT_DOWN && videoUnits != unitsAtDrop) downSinceMs = NOT_DOWN
            if (downSinceMs == NOT_DOWN && !androidAutoUp) {
                downSinceMs = nowMs
                unitsAtDrop = videoUnits
            }
        }
        val waiting = downSinceMs != NOT_DOWN && nowMs - downSinceMs >= afterMs
        if (waiting == isWaiting) return null
        isWaiting = waiting
        return waiting
    }

    companion object {
        /** A drop that lasts longer than this shows the notice: the redial brings the picture back well before it in the ordinary case. */
        const val AFTER_MS = 5_000L

        private const val NOT_DOWN = -1L
    }
}
