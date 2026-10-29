package com.reau.app.stream

import com.reau.app.data.RideDiagnostics

/**
 * What the rider feels of the joystick, measured on the phone's side for one touch at a time: from the key sent to Android Auto, to its
 * first new picture ([aaMs]: Android Auto's own reaction), to that picture drawn by the compositor ([drawnMs]: the wait for the tick
 * included), to the first packet of video that goes to the dash after it ([wireMs]). The rest (the dash's decoding and drawing) cannot
 * be measured from here. One touch is followed at a time; the next one waits for it to finish or [timeoutNs] to pass. Pure, with the
 * times passed in, so the JVM tests cover it; [InputLatency] is the one the app feeds.
 */
internal class InputLatencyClock(private val timeoutNs: Long = TIMEOUT_NS) {
    /** The times of a finished measurement, all counted from the key (ms). */
    data class Measurement(val aaMs: Long, val drawnMs: Long, val wireMs: Long)

    private enum class Stage { IDLE, WAIT_PICTURE, WAIT_DRAW, WAIT_WIRE }

    private var stage = Stage.IDLE

    /** Whether the next packet of video finishes the measurement: the packets of a stream far outnumber the touches, so they ask this first. */
    val waitingForPacket: Boolean get() = stage == Stage.WAIT_WIRE
    private var keyAt = 0L
    private var arrivedAt = 0L
    private var drawnAt = 0L

    /** A key went to Android Auto at [nowNs]: it starts a measurement if none is under way. */
    fun keySent(nowNs: Long) {
        if (stage != Stage.IDLE && nowNs - keyAt <= timeoutNs) return
        stage = Stage.WAIT_PICTURE
        keyAt = nowNs
    }

    /** A new picture from Android Auto arrived at [nowNs]. */
    fun pictureArrived(nowNs: Long) {
        if (expired(nowNs)) return
        if (stage == Stage.WAIT_PICTURE) {
            arrivedAt = nowNs
            stage = Stage.WAIT_DRAW
        }
    }

    /** The compositor drew a new picture (not a repeat) at [nowNs]. */
    fun pictureDrawn(nowNs: Long) {
        if (expired(nowNs)) return
        if (stage == Stage.WAIT_DRAW) {
            drawnAt = nowNs
            stage = Stage.WAIT_WIRE
        }
    }

    /** A packet of video went to the dash at [nowNs]: the measurement, when this is the one that finishes it. */
    fun packetSent(nowNs: Long): Measurement? {
        if (expired(nowNs) || stage != Stage.WAIT_WIRE) return null
        stage = Stage.IDLE
        return Measurement(ms(arrivedAt - keyAt), ms(drawnAt - keyAt), ms(nowNs - keyAt))
    }

    private fun expired(nowNs: Long): Boolean {
        if (stage != Stage.IDLE && nowNs - keyAt > timeoutNs) stage = Stage.IDLE
        return stage == Stage.IDLE
    }

    private fun ms(ns: Long) = ns / NS_PER_MS

    companion object {
        /** A key that moves nothing (no new picture within this) is dropped, and the next one is followed. */
        const val TIMEOUT_NS = 1_500_000_000L
        private const val NS_PER_MS = 1_000_000L
    }
}

/** The one the app feeds: the key ([com.reau.app.aa.AaScreens.inputSent]), the picture, the draw and the packet; a line in the ride log per touch. */
internal object InputLatency {
    private val clock = InputLatencyClock()
    @Volatile private var waitingForPacket = false

    @Synchronized fun keySent() {
        clock.keySent(System.nanoTime())
    }

    @Synchronized fun pictureArrived() {
        clock.pictureArrived(System.nanoTime())
    }

    @Synchronized fun pictureDrawn() {
        clock.pictureDrawn(System.nanoTime())
        waitingForPacket = clock.waitingForPacket
    }

    /** A packet of video went to the dash. [fps] is the pace in force and [screen] the Android Auto screen in front, for the line. */
    fun packetSent(fps: () -> Int, screen: () -> String) {
        if (!waitingForPacket) return
        val done = synchronized(this) { clock.packetSent(System.nanoTime()).also { waitingForPacket = clock.waitingForPacket } } ?: return
        RideDiagnostics.log("input", line(done, screen(), fps()))
    }

    internal fun line(m: InputLatencyClock.Measurement, screen: String, fps: Int): String =
        "joystick: Android Auto answered +${m.aaMs} ms, drawn +${m.drawnMs} ms, on the wire +${m.wireMs} ms after the key (screen $screen, $fps fps)"
}
