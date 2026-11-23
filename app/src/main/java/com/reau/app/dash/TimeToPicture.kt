package com.reau.app.dash

import java.util.Locale

/**
 * How long a connection took to put the picture on the dash, written on one line when the first keyframe of the stream has gone out (plan 32, C14).
 * Four marks of the monotonic clock, one stage between each pair: the request for the dash's network, the network up, the dash authenticated, the
 * stream started (the first keyframe goes out right after). Only a log line: nothing reads it, nothing is done differently for it.
 *
 * A connection is whatever lies between two lines: the first request of it is the one kept (a request that is let go and asked for again counts
 * from the first), the network and the authentication are the last of them (a network that came back, a session that began again). A mark that
 * was not made (the network was up when the session began, so no request) shows as `?` in its stages, with the reason, and the total counts from
 * the first mark there is. After the line every mark is cleared: the next connection starts over.
 */
internal class TimeToPictureMarks {
    private var requestMs: Long? = null
    private var networkMs: Long? = null
    private var authMs: Long? = null
    private var streamingMs: Long? = null

    /** The dash's network was asked for at [nowMs]; only the first request of a connection counts. */
    @Synchronized
    fun requested(nowMs: Long) {
        if (requestMs == null) requestMs = nowMs
    }

    /** The dash's network is up (asked for, or found already up) at [nowMs]. */
    @Synchronized
    fun networkUp(nowMs: Long) {
        networkMs = nowMs
    }

    /** The dash accepted the session at [nowMs]; a stream that had begun and not shown a keyframe is forgotten. */
    @Synchronized
    fun authenticated(nowMs: Long) {
        authMs = nowMs
        streamingMs = null
    }

    /** The session went to STREAMING at [nowMs]: the next keyframe sent is the one the line waits for. */
    @Synchronized
    fun streaming(nowMs: Long) {
        streamingMs = nowMs
    }

    /** A keyframe went out at [nowMs]: the line of the connection if it is the first one of a stream, else null (and nothing changes). */
    @Synchronized
    fun keyframeSent(nowMs: Long): String? {
        if (streamingMs == null) return null
        return lineAt(nowMs).also { forget() }
    }

    /** A new projection starts over, and so does an unfinished connection. */
    @Synchronized
    fun forget() {
        requestMs = null
        networkMs = null
        authMs = null
        streamingMs = null
    }

    private fun lineAt(keyframeMs: Long): String {
        val marks = listOf(requestMs, networkMs, authMs, keyframeMs)
        val start = marks.firstNotNullOfOrNull { it }
        val stages = listOf(
            "request→network" to stage(requestMs, networkMs),
            "network→auth" to stage(networkMs, authMs),
            "auth→first keyframe" to stage(authMs, keyframeMs),
        )
        val total = if (start == null) "?" else seconds(keyframeMs - start)
        val from = when {
            requestMs != null || start == null -> ""
            networkMs != null -> " from the network"
            else -> " from the authentication"
        }
        val why = listOfNotNull(
            "no request in this connection, the network was already up".takeIf { requestMs == null },
            "no mark when the network came up".takeIf { networkMs == null && requestMs != null },
            "no mark of the authentication".takeIf { authMs == null },
        ).joinToString(", ").ifEmpty { "the marks came out of order" }
        val unknown = if (stages.any { it.second == UNKNOWN }) "; ?: $why" else ""
        return "time to picture: $total$from (" + stages.joinToString(", ") { "${it.first} ${it.second}" } + unknown + ")"
    }

    private fun stage(from: Long?, to: Long?): String = if (from == null || to == null || to < from) UNKNOWN else seconds(to - from)

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / MS_PER_S.toDouble())

    private companion object {
        const val UNKNOWN = "?"
        const val MS_PER_S = 1_000L
    }
}

/** The one set of marks of the process (one projection at a time; [ImageGap] is the same). */
internal object TimeToPicture {
    val marks = TimeToPictureMarks()
}
