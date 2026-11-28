package com.reau.app.aa

/**
 * The first thing the receiver does when a session of its own ends (the part of `AaReceiver.afterSession` that needs no phone, so a JVM test can drive it):
 * the end is told apart as one that followed a change of the USB ([UsbSessionEnd]), the music that played waits for the next session when it did
 * ([MusicResume]), and the streak of unanswered handshakes ([HandshakeStreak]) says whether this is a plain drop or Android Auto not answering.
 */
internal object SessionEnd {
    /**
     * Settles a session that ended now. The end is always asked of [usb] (one change explains one end, answered or not), and said through [say] when it
     * followed a change. [musicEnded] is told whether the USB ended it, only for a session whose handshake was answered ([handshook]).
     * Returns the [UsbSessionEnd.Ending] to pause with before dialling again when [unanswered] still counts this as a plain drop, and null when
     * Android Auto does not answer (the caller then waits as for an unresponsive server).
     */
    fun settle(
        usb: UsbSessionEnd,
        unanswered: HandshakeStreak,
        handshook: Boolean,
        say: (String) -> Unit,
        musicEnded: (byUsb: Boolean) -> Unit,
    ): UsbSessionEnd.Ending? {
        val ending = usb.noteEnd(say)
        if (handshook) musicEnded(ending.sinceMs != null)
        return if (unanswered.after(handshook)) null else ending
    }
}
