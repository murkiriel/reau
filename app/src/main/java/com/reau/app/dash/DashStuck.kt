package com.reau.app.dash

/**
 * The dash whose Wi-Fi hung (ride log of 01/11/2026, 19:12 to 19:16): the dash went quiet in the middle of the ride, Android lost its
 * network, and for 3 min 54 s the dash stayed in the Wi-Fi scan with a good signal (-47 to -63 dBm, the same channel) while 23 requests in a
 * row ended unavailable in 6 s each. The rest of the dash (speed, trip) worked, only the picture was gone; it came back when the rider switched
 * the ignition off and on. REau cannot undo that, but it can tell the rider what does: a notification, once for the absence.
 *
 * Told apart from the bike being switched off by the scan: a dash that is off leaves it, a hung one stays. Pure, so the JVM tests cover it
 * (StuckDashWatchTest); the Wi-Fi manager feeds it the facts and shows the notification.
 *
 * This covers the dash that hangs **in the middle of a ride** (the Wi-Fi was joined, spoke, was lost). The older detection,
 * [JoinRules.Refusal.DASH_STUCK] (tested by DashStuckTest), is only evaluated in the manager's "initial connect" branch (before any join
 * of the projection, or after a quick refusal of a network that had worked) and only puts the words `dash_err_dash_stuck` on the screen; it
 * raises no notification. The two are independent.
 */
internal object DashStuck {
    /** Failed tries in a row, with the dash in the scan, that make it hung. Each try is about 10 s of request and pause (19:12:55 to 19:13:36: five). */
    const val MIN_TRIES = 3

    /** And the dash must have been away at least this long: three tries are about 30 s, which a restarting dash does not take. */
    const val MIN_ABSENT_MS = 30_000L

    /** A signal at least this good is a dash within reach: one that refuses is not too far to join. */
    const val GOOD_SIGNAL_DBM = -75

    /** The sighting must be at most this old to count as "there now" (the logs show them 3 to 20 s old with the screen on). */
    const val FRESH_SIGHTING_MS = 15_000L

    /**
     * The dash counts as missing from a scan list when its newest result is this much older than the list's newest result of any network: the
     * networks of one scan are stamped within a few seconds of each other, so a dash heard in the newest scan is never this far behind.
     */
    const val MISSED_BY_MS = 10_000L

    private const val MICROS_PER_MILLI = 1_000L

    /** What the scan says of the dash at the end of a try: it counts, it says nothing, or the dash is gone. */
    enum class Sighting { GOOD, UNKNOWN, GONE }

    /**
     * The scan [seen] as it bears on "the dash is there and could be joined" at [nowUs] (microseconds since boot, as the scan's stamps).
     * A readable scan with the dash not in it, or only as an entry older than [DashJoin.SIGHTING_FRESH_MS] (the list keeps a switched-off
     * dash for minutes), is [Sighting.GONE]: the bike is off or away. Fresh and at [GOOD_SIGNAL_DBM] or better is [Sighting.GOOD]. A scan that
     * cannot be read or is empty (the screen is off), a sighting between the two ages or a weak one say nothing: [Sighting.UNKNOWN].
     */
    fun sighting(seen: List<ScanSeen>?, wantedSsid: String, wantedIsPrefix: Boolean, nowUs: Long): Sighting {
        val dash = seen.orEmpty().filter { DashSsid.matchesPendingSsid(it.ssid, wantedSsid, wantedIsPrefix) }
        val freshest = dash.filter { it.timestampUs in 1..nowUs }.maxByOrNull { it.timestampUs }
        val ageMs = freshest?.let { (nowUs - it.timestampUs) / MICROS_PER_MILLI }
        return when {
            seen.isNullOrEmpty() -> Sighting.UNKNOWN
            dash.isEmpty() || !DashJoin.seenRecently(dash, wantedSsid, wantedIsPrefix, nowUs) -> Sighting.GONE
            ageMs != null && ageMs <= FRESH_SIGHTING_MS && dash.maxOf { it.level } >= GOOD_SIGNAL_DBM -> Sighting.GOOD
            else -> Sighting.UNKNOWN
        }
    }

    /** One look at the scan list for a failed try: the list as read ([seen], null when unreadable) and what it says of the dash. */
    data class Look(val seen: List<ScanSeen>?, val sighting: Sighting)

    /**
     * Whether [seen] shows the dash gone since [notBeforeUs] (microseconds since boot, as the scan's stamps; [nowUs] is now in the same base):
     * the list has a result of some network stamped at or after [notBeforeUs] (a scan made after that moment, not an old list read again) and
     * the dash is not in it, or only with results older than that newest one by [MISSED_BY_MS] (that scan did not hear it). A short ignition
     * off and on shows up so, minutes before the list lets go of the dash ([DashJoin.SIGHTING_FRESH_MS]). Unreadable, empty, unstamped lists
     * and a dash with an unstamped result say nothing: false.
     */
    fun vanishedFromList(seen: List<ScanSeen>?, wantedSsid: String, wantedIsPrefix: Boolean, nowUs: Long, notBeforeUs: Long): Boolean {
        val newest = seen.orEmpty().map { it.timestampUs }.filter { it in 1..nowUs }.maxOrNull() ?: return false
        if (newest < notBeforeUs) return false
        return seen.orEmpty().filter { DashSsid.matchesPendingSsid(it.ssid, wantedSsid, wantedIsPrefix) }
            .all { it.timestampUs in 1..nowUs && newest - it.timestampUs > MISSED_BY_MS * MICROS_PER_MILLI }
    }

    /** The line for the ride log, said once for an absence. */
    fun line(absentMs: Long, tries: Int, rssiDbm: Int?): String =
        "dash visible but refusing for ${absentMs / 1_000} s ($tries tries${rssiDbm?.let { ", $it dBm" } ?: ""}) — " +
            "dash Wi-Fi likely hung; rider told to cycle ignition"

    /**
     * What a verdict says: how many tries and how long. [remembered]: it was given at the first try because [StuckMemory] still held the
     * last verdict of the same dash, not because [MIN_TRIES] tries went by.
     */
    data class Verdict(val tries: Int, val absentMs: Long, val remembered: Boolean = false)

    /** The line for the ride log of a verdict given at once by [StuckMemory]: [sinceMs] after the last one, with the dash at [rssiDbm]. */
    fun rememberedLine(sinceMs: Long, rssiDbm: Int?): String =
        "dash still hung since the last verdict ${sinceMs / 1_000} s ago (first try of this start${rssiDbm?.let { ", $it dBm" } ?: ""}): " +
            "the notice is shown at once; only the ignition clears it"
}

/**
 * What the process remembers of a hung dash across the rider's switching REau's projection off and on (ride log of 21/11/2026, 07:25 to 07:29):
 * the dash's Wi-Fi hung for real at 07:25:25, [StuckDashWatch] gave the verdict at 07:26:00 and the rider was told to cycle the ignition. He
 * switched the projection off and on three times instead. Each switch-off took the notice away ([DashWifiManager.disconnect]) and the next start
 * needed three quick refusals again (47 s) before the words came back, with no notification at all in the initial connect. Only the ignition
 * clears a hung dash, so the verdict outlives the projection: the first failed try of the next start, with the dash in the scan at a good
 * signal ([DashStuck.Sighting.GOOD]), shows the notice at once.
 *
 * Forgotten when the dash is really joined (it is back), when a try ends with the dash gone from the scan ([DashStuck.Sighting.GONE]: the
 * ignition was switched off, which is what clears it), when a list read after the verdict shows the dash missing ([noteList]: the same, for an
 * ignition cycle so short that no try fell inside it; a hung dash never leaves the list), and [REMEMBER_MS] after the last verdict. NOT forgotten by the manual disconnect, which is
 * the very case. Pure: the monotonic clock is given, not read. Thread-safe: the manager's callbacks and the projection call it.
 */
internal object StuckMemory {
    /** How long a verdict is kept: a rider who gave up and tried again later than this deserves the full check again. */
    const val REMEMBER_MS = 300_000L

    private var ssid: String? = null
    private var prefix = false
    private var verdictAtMs = 0L
    private const val MICROS_PER_MILLI = 1_000L

    /** The dash called [pendingSsid] ([pendingPrefix]: it is a prefix) was judged hung at [nowMs]. */
    @Synchronized
    fun remember(pendingSsid: String, pendingPrefix: Boolean, nowMs: Long) {
        ssid = pendingSsid
        prefix = pendingPrefix
        verdictAtMs = nowMs
    }

    /**
     * A look at the scan list [seen] at [nowUs] (microseconds since boot, as the scan's stamps) and [nowMs] (this object's clock) for the dash
     * called [pendingSsid]: if it shows that dash gone since the verdict ([DashStuck.vanishedFromList]), the ignition was cycled and the
     * verdict goes, even when it is back in the list by the next try. Any look of the same dash may call it; another dash's list says nothing.
     */
    @Synchronized
    fun noteList(seen: List<ScanSeen>?, pendingSsid: String, pendingPrefix: Boolean, nowUs: Long, nowMs: Long) {
        val held = ssid ?: return
        if (!sameDash(held, prefix, pendingSsid, pendingPrefix)) return
        val verdictUs = nowUs - (nowMs - verdictAtMs) * MICROS_PER_MILLI
        if (DashStuck.vanishedFromList(seen, pendingSsid, pendingPrefix, nowUs, verdictUs)) ssid = null
    }

    /** The dash is back, or the ignition was switched off: nothing is held. */
    @Synchronized
    fun forget() {
        ssid = null
    }

    /**
     * For a try that just failed at [nowMs] with the dash [sighting] in the scan: how long ago the last verdict of the same dash was when it
     * still holds and the dash is there ([DashStuck.Sighting.GOOD]), else null. A try with the dash gone forgets the verdict
     * ([DashStuck.Sighting.GONE]); one the scan says nothing about ([DashStuck.Sighting.UNKNOWN]) neither fires nor forgets. A verdict older
     * than [REMEMBER_MS] is forgotten too.
     */
    @Synchronized
    fun sinceVerdictMs(pendingSsid: String, pendingPrefix: Boolean, sighting: DashStuck.Sighting, nowMs: Long): Long? {
        val held = ssid ?: return null
        val since = nowMs - verdictAtMs
        if (since !in 0..REMEMBER_MS || sighting == DashStuck.Sighting.GONE) {
            ssid = null
            return null
        }
        return since.takeIf { sighting == DashStuck.Sighting.GOOD && sameDash(held, prefix, pendingSsid, pendingPrefix) }
    }

    /** The same dash: equal names, or one a prefix of the other's (a start by `RE_` and one by the exact name saved from the first). */
    private fun sameDash(a: String, aPrefix: Boolean, b: String, bPrefix: Boolean): Boolean = when {
        aPrefix && bPrefix -> a.startsWith(b) || b.startsWith(a)
        aPrefix -> DashSsid.matchesPendingSsid(b, a, true)
        bPrefix -> DashSsid.matchesPendingSsid(a, b, true)
        else -> a == b
    }
}

/**
 * Keeps the facts of one absence of the dash (from the Wi-Fi lost to the Wi-Fi back) and says, once, when it is a hung dash: it had spoken
 * in this projection ([lost] with `spoke`), and since the loss [DashStuck.MIN_TRIES] tries in a row ended with the dash in the scan with a good
 * signal ([failedTry]), over at least [DashStuck.MIN_ABSENT_MS]. A try with the dash gone from the scan starts the count over: that is the bike
 * switched off. Times are given (monotonic milliseconds), not read. Thread-safe: the system's callbacks and the watch call it.
 */
internal class StuckDashWatch {
    private var lostAtMs: Long? = null
    private var spoke = false
    private var tries = 0
    private var told = false

    /** The Wi-Fi was lost at [nowMs]; [dashSpoke]: the dash had spoken in this projection. Only the first loss of an absence counts. */
    @Synchronized
    fun lost(nowMs: Long, dashSpoke: Boolean) {
        if (lostAtMs != null) return
        lostAtMs = nowMs
        spoke = dashSpoke
        tries = 0
        told = false
    }

    /**
     * A try for the dash's Wi-Fi ended without it, with the dash [sighting] in the scan; the verdict the first time it is a hung dash, else null.
     * [remembered]: [StuckMemory] still holds a verdict of this dash. Then the first try with the dash [DashStuck.Sighting.GOOD] is the verdict,
     * with no loss, no speech and no count needed (21/11/2026: a new start after the rider switched the projection off is not a new absence).
     */
    @Synchronized
    fun failedTry(nowMs: Long, sighting: DashStuck.Sighting, remembered: Boolean = false): DashStuck.Verdict? {
        if (remembered && sighting == DashStuck.Sighting.GOOD && !told) {
            told = true
            return DashStuck.Verdict(tries = 1, absentMs = lostAtMs?.let { nowMs - it } ?: 0L, remembered = true)
        }
        val lostAt = lostAtMs ?: return null
        if (!spoke || told) return null
        when (sighting) {
            DashStuck.Sighting.GONE -> tries = 0
            DashStuck.Sighting.UNKNOWN -> Unit
            DashStuck.Sighting.GOOD -> tries++
        }
        val absent = nowMs - lostAt
        if (tries < DashStuck.MIN_TRIES || absent < DashStuck.MIN_ABSENT_MS) return null
        told = true
        return DashStuck.Verdict(tries, absent)
    }

    /** The dash is back (or the rider switched off): the absence is over. True when the rider had been told, so the notice goes. */
    @Synchronized
    fun back(): Boolean {
        val wasTold = told
        lostAtMs = null
        spoke = false
        tries = 0
        told = false
        return wasTold
    }
}
