package com.reau.app.aa

/**
 * Test tool: hands a recorded session ([SessionRecorder]) to a message handler again, message by message, as the
 * read loop of a live session would. With a recording made on the phone, a test on the emulator gets what the real
 * Android Auto sent.
 */
internal object SessionReplayer {
    /** No wait between two messages is longer than this when [replay] keeps the recorded pace. */
    private const val LONGEST_WAIT_MS = 2_000L

    /**
     * Feeds [handler] with every message Android Auto sent in [recording], in order (not the ones REau sent); returns how many. [paced] waits between
     * messages as long as the recording says (a decoder watchdog or a timeout may depend on it); otherwise they go
     * one right after the other.
     */
    fun replay(recording: SessionRecording, handler: AapMessageHandler, paced: Boolean = false): Int {
        var previousMs = 0L
        // What REau sent to Android Auto is in the file too, and is not for the handler.
        val received = recording.messages.filterNot { it.sent }
        for (recorded in received) {
            if (paced) Thread.sleep((recorded.timeMs - previousMs).coerceIn(0, LONGEST_WAIT_MS))
            previousMs = recorded.timeMs
            handler.handle(messageOf(recorded))
        }
        return received.size
    }

    /** The message as the read loop builds it: the payload begins with the two type bytes. */
    fun messageOf(recorded: RecordedMessage): AapMessage =
        AapMessage(recorded.channel, recorded.flags.toByte(), recorded.type, 2, recorded.payload.size, recorded.payload)
}
