package com.reau.app.dash

/**
 * The instant the dash first says it decoded a frame of the stream, once per session (plan 35, a report of 25/11/2026). Until then the log only had the 5 s
 * `video fps` windows, so the reader could say no more than "between 5.4 and 10.5 s after streaming began". The dash's notice of a decoded frame (`09 06 55` for a
 * keyframe, `09 04 55` for a P-frame; the reply is `06 11` / `06 12`) that comes while the session is STREAMING is the first one: the line says how long after
 * `→ STREAMING` it came. A notice before the stream (the dash's own 10 s mark, with no video at all, 20/10/2026) is not a frame of this stream and is not told.
 */
internal class FirstFrameMark {
    private var told = false

    /** A new session: the next stream's first confirmation is told again. */
    @Synchronized
    fun reset() {
        told = false
    }

    /**
     * A decode notice came in [state] at [nowMs]; [streamingStartMs] is when the session went to STREAMING (0: it has not). The milliseconds since then, for the
     * first notice of the session that came while streaming; null for the others.
     */
    @Synchronized
    fun ack(state: DashState, streamingStartMs: Long, nowMs: Long): Long? {
        if (told || state != DashState.STREAMING || streamingStartMs <= 0L) return null
        told = true
        return (nowMs - streamingStartMs).coerceAtLeast(0L)
    }

    companion object {
        /** The log's line; [notice] is the dash's notice that confirmed it (`09 06 55`). The reader (`ler_registro.py`) takes the number out of it. */
        fun line(ms: Long, notice: String): String = "first frame confirmed by the dash $ms ms after streaming began ($notice)"
    }
}
