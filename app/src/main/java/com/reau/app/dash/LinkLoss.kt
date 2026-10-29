package com.reau.app.dash

import java.util.Locale

/**
 * What the phone knew of the dash's Wi-Fi network while it was up, kept for the line written when it is lost.
 * Every field is null when Android did not say; [upAtMs] is on the [com.reau.app.util.MonotonicClock]. No SSID, no BSSID.
 *
 * @property specifier the network came from REau's own request (a `WifiNetworkSpecifier`), not one the phone joined by itself.
 */
internal data class NetFacts(
    val upAtMs: Long,
    val mhz: Int? = null,
    val rssiDbm: Int? = null,
    val specifier: Boolean? = null,
    val validated: Boolean? = null,
    val notRestricted: Boolean? = null,
)

/**
 * The words of the dash's Wi-Fi going and coming back, for the ride log: the dash's access point is suspected of
 * restarting by itself (4 drops in 6 min in the "gaúcho" logs), and the channel it comes back on tells it. Pure, so the JVM tests cover it.
 */
internal object LinkLoss {
    private const val MS_PER_S = 1_000.0
    private const val MS_PER_SECOND = 1_000L
    private const val UNKNOWN = "unknown"

    /** Whether the dash came back on the channel it left. */
    enum class Channel { FIRST, SAME, OTHER }

    /** The channel of the new connection against the one of the last connection of this projection ([before] null: there was none known). */
    fun channel(before: Int?, now: Int): Channel = when {
        before == null -> Channel.FIRST
        before == now -> Channel.SAME
        else -> Channel.OTHER
    }

    /**
     * The one line for a lost network: `link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -42, mechanism=specifier,
     * validated=false, restricted=false, last dash packet 0.2 s before, screen=on)`. It begins as it always did, so the log reader and the
     * old logs agree. What is not known says `unknown`, never a made-up number. [packetAgoMs] is how long before the loss the dash
     * last sent anything (null: nothing this projection).
     */
    fun lostLine(facts: NetFacts?, nowMs: Long, packetAgoMs: Long?, screenOn: Boolean?, reconnectInMs: Long): String {
        val after = facts?.let { "${(nowMs - it.upAtMs).coerceAtLeast(0) / MS_PER_SECOND} s" } ?: "an $UNKNOWN time"
        val channel = facts?.mhz?.let { "$it MHz" } ?: "an $UNKNOWN channel"
        val mechanism = when (facts?.specifier) {
            true -> "specifier"
            false -> "phone-joined"
            null -> UNKNOWN
        }
        val rest = listOf(
            "rssi ${facts?.rssiDbm ?: UNKNOWN}",
            "mechanism=$mechanism",
            "validated=${flag(facts?.validated)}",
            "restricted=${flag(facts?.notRestricted?.not())}",
            "last dash packet ${packetAgoMs?.let { "${tenths(it)} s before" } ?: UNKNOWN}",
            "screen=${flag(screenOn, "on", "off")}",
        ).joinToString(", ")
        return "link lost — reconnecting in ${reconnectInMs}ms (after $after on $channel; $rest)"
    }

    /** `the dash's Wi-Fi is back on another channel (2447 → 2427 MHz): its access point restarted`; null on the projection's first connection. */
    fun backLine(before: Int?, now: Int): String? = when (channel(before, now)) {
        Channel.FIRST -> null
        Channel.SAME -> "the dash's Wi-Fi is back on the same channel ($now MHz)"
        Channel.OTHER -> "the dash's Wi-Fi is back on another channel ($before → $now MHz): its access point restarted"
    }

    /** The line for Android's warning that a network is about to go. */
    fun losingLine(maxMsToLive: Int): String = "link losing: Android will drop the dash's Wi-Fi within $maxMsToLive ms"

    private fun tenths(ms: Long) = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0) / MS_PER_S)

    private fun flag(v: Boolean?, yes: String = "true", no: String = "false") = when (v) {
        null -> UNKNOWN
        true -> yes
        false -> no
    }
}

/**
 * What the Wi-Fi manager asks of the dash session, which it has no handle on: when the dash last sent a packet (monotonic clock;
 * 0 = none yet) and the session's [PacketTrail], for the lines written when the Wi-Fi goes.
 */
internal object DashHeard {
    @Volatile var trail: PacketTrail? = null

    @Volatile var atMs = 0L
        private set

    fun heard(nowMs: Long) {
        atMs = nowMs
    }

    /** How long before [nowMs] the dash was last heard, or null if never; a new projection starts over ([forget]). */
    fun agoMs(nowMs: Long): Long? = atMs.takeIf { it > 0 }?.let { (nowMs - it).coerceAtLeast(0) }

    fun forget() {
        atMs = 0L
    }
}
