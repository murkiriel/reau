package com.reau.app.video

import java.util.Locale
import kotlin.math.ceil

/**
 * How long a new picture from Android Auto waits for the compositor's next tick before it is drawn (and so before the dash can see
 * it): from the moment the picture arrives to the draw. The tick is a fixed grid (`1000/fps`), so on a still screen the wait is
 * anywhere from 0 to one interval: 125 ms on average at 4 fps, 250 ms at 2 fps. This only
 * measures it, for the `tickwait=` field of the `video fps` line; it changes nothing about the pace.
 */
class TickWaitClock {
    private var firstNs = NONE

    /** A new picture arrived at [nowNs]; only the first one since the last draw starts the wait (the draw shows the newest). */
    fun arrived(nowNs: Long) {
        if (firstNs == NONE) firstNs = nowNs
    }

    /** A new picture was drawn at [nowNs]: how many ms it waited, or null if none was waiting. */
    fun drawn(nowNs: Long): Double? {
        val first = firstNs
        firstNs = NONE
        return if (first == NONE) null else (nowNs - first) / NS_PER_MS
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val NS_PER_MS = 1_000_000.0
    }
}

/** The waits of one measurement window, handed from the compositor's thread to the governor's. */
class TickWaitLog {
    private val samples = ArrayList<Double>()

    @Synchronized
    fun add(ms: Double) {
        samples += ms
    }

    /** What was measured since the last call; it starts again. */
    @Synchronized
    fun take(): List<Double> = samples.toList().also { samples.clear() }
}

object TickWait {
    private const val P95 = 0.95

    /** `125/240/250ms` (mean / 95th percentile / worst) of [samples], or `-` when no new picture was drawn in the window. */
    fun text(samples: List<Double>): String {
        if (samples.isEmpty()) return "-"
        val sorted = samples.sorted()
        val p95 = sorted[(ceil(P95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return String.format(Locale.US, "%.0f/%.0f/%.0fms", samples.average(), p95, sorted.last())
    }
}
