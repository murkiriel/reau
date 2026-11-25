package com.reau.app.dash

/**
 * How crowded the dash's Wi-Fi channel is, from the scan list Android already has in its cache, for the ride log only (no decision reads it): the
 * line `channel: dash 2452 MHz; same channel 3 (strongest -71 dBm), overlapping 5 (strongest -64 dBm); cache 42 s old`. Nothing is scanned for it
 * (the list is only read: a scan asked for by the app disturbs the video), and the line carries only counts, frequencies and dBm: never the name
 * (SSID) or the address (BSSID, MAC) of any network, the dash's included. Pure, so the JVM tests cover it.
 */
object ChannelCrowd {
    /** How often the line goes, in 1 Hz heartbeat ticks. */
    const val EVERY_TICKS = 30

    /** The line said once when the cache has no result (or Android would not give it): not again every [EVERY_TICKS] s. */
    const val EMPTY_LINE = "channel: no scan results cached"

    /** In 2.4 GHz a channel overlaps the ones up to 4 channels (5 MHz each) away. */
    private const val OVERLAP_MHZ = 20
    private const val BAND_24_BELOW_MHZ = 3_000
    private const val MICROS_PER_SECOND = 1_000_000L

    /**
     * One result of the scan cache. [ssid] is read only to leave the dash itself out of the count; it is never written ([ssid] is `""` for a hidden network).
     * [timestampUs]: microseconds since boot, as `ScanResult.timestamp` says.
     */
    data class Row(val ssid: String, val mhz: Int, val dbm: Int, val timestampUs: Long)

    /** The others on [mhz] and the ones overlapping it, as the line says them. */
    data class Crowd(val same: Int, val sameStrongest: Int?, val overlapping: Int, val overlappingStrongest: Int?, val cacheAgeS: Long)

    /** Whether the cache says nothing to count: no list (unreadable, no permission) or an empty one. */
    fun isEmpty(rows: List<Row>?): Boolean = rows.isNullOrEmpty()

    /**
     * The crowd around the dash's channel, [dashMhz], at [nowUs] (microseconds since boot); the dash's own network ([dashSsid], when known) is not counted. The
     * overlap is for 2.4 GHz only (another network 1 to 4 channels away, 5 to 20 MHz): in 5 and 6 GHz only the same channel counts, as the channels are 20 MHz apart.
     */
    fun crowd(rows: List<Row>, dashMhz: Int, dashSsid: String?, nowUs: Long): Crowd {
        val others = rows.filter { dashSsid.isNullOrEmpty() || it.ssid != dashSsid }
        val same = others.filter { it.mhz == dashMhz }
        val near = if (dashMhz < BAND_24_BELOW_MHZ) {
            others.filter { it.mhz < BAND_24_BELOW_MHZ && it.mhz != dashMhz && kotlin.math.abs(it.mhz - dashMhz) <= OVERLAP_MHZ }
        } else {
            emptyList()
        }
        val newest = rows.maxOf { it.timestampUs }
        return Crowd(
            same.size, same.maxOfOrNull { it.dbm }, near.size, near.maxOfOrNull { it.dbm },
            ((nowUs - newest) / MICROS_PER_SECOND).coerceAtLeast(0),
        )
    }

    /**
     * What the log says at each look at the cache: the line, or, with nothing cached, [EMPTY_LINE] **once** (null at the looks after, until a list shows again).
     * One per session; not thread-safe (the heartbeat's coroutine drives it).
     */
    class Teller {
        private var emptyTold = false

        fun look(rows: List<Row>?, dashMhz: Int, dashSsid: String?, nowUs: Long): String? {
            if (isEmpty(rows)) return if (emptyTold) null else EMPTY_LINE.also { emptyTold = true }
            emptyTold = false
            return line(checkNotNull(rows), dashMhz, dashSsid, nowUs)
        }
    }

    /** The line for [rows] (not empty), the dash being on [dashMhz]; see [crowd]. */
    fun line(rows: List<Row>, dashMhz: Int, dashSsid: String?, nowUs: Long): String {
        val c = crowd(rows, dashMhz, dashSsid, nowUs)
        fun part(count: Int, strongest: Int?) = if (strongest != null) "$count (strongest $strongest dBm)" else "$count"
        val same = part(c.same, c.sameStrongest)
        val overlapping = part(c.overlapping, c.overlappingStrongest)
        return "channel: dash $dashMhz MHz; same channel $same, overlapping $overlapping; cache ${c.cacheAgeS} s old"
    }
}
