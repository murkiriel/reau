package com.reau.app.dash.nav

import com.reau.app.settings.DrivingSideMode

/**
 * Which side of the road the rider is on, for the maneuvers Android Auto does not give a side or a direction to: a roundabout without a direction
 * (types 30 and 31) and a U-turn without a side. What Android Auto does say (a roundabout clockwise or counter-clockwise) never goes through here.
 *
 * The rider's [mode] decides. [DrivingSideMode.RIGHT_HAND] and [DrivingSideMode.LEFT_HAND] force the side and the country is not read. In
 * [DrivingSideMode.AUTO] the side comes from the country of the mobile network, else of the SIM; nothing needs a permission, the telephony service tells
 * both to any app. With neither, the side is right-hand traffic, as it always was.
 *
 * The country is read through [networkCountry] and [simCountry] (ISO 3166 alpha-2, any case, null or blank when unknown), so the JVM tests run with made-up
 * readers. A read is cheap, and the country can change on a border, so [side] reads again once [refreshMs] has passed since the last read, and at once when the
 * mode changed; [refresh] reads now (the start of the projection). [log] gets one line when the side is first decided and each time it or its source
 * changes: the side and where it came from, never the country, which is the rider's whereabouts.
 */
class DrivingSideSource(
    private val clockMs: () -> Long,
    private val networkCountry: () -> String?,
    private val simCountry: () -> String?,
    private val log: (String) -> Unit,
    private val mode: () -> DrivingSideMode = { DrivingSideMode.AUTO },
    private val refreshMs: Long = REFRESH_MS,
) {
    private enum class From(val why: String) {
        SETTINGS("chosen in settings"),
        NETWORK("from the mobile network"),
        SIM("from the SIM card"),
        NONE("default, no mobile network country"),
    }

    private var decided: Pair<DrivingSide, From>? = null
    private var decidedMode: DrivingSideMode? = null
    private var readAtMs = 0L

    /** The side now: the last decision, or a new one when none was made yet, the mode changed, or (in automatic) [refreshMs] has passed since the last read. */
    @Synchronized
    fun side(): DrivingSide {
        val last = decided
        val current = mode()
        val stale = current == DrivingSideMode.AUTO && clockMs() - readAtMs >= refreshMs
        return if (last == null || current != decidedMode || stale) refresh() else last.first
    }

    /** Decides now (reading the country only in automatic), says the side if it is new, and returns it. */
    @Synchronized
    fun refresh(): DrivingSide {
        val current = mode()
        val now = when (current) {
            DrivingSideMode.RIGHT_HAND -> DrivingSide.RIGHT_HAND to From.SETTINGS
            DrivingSideMode.LEFT_HAND -> DrivingSide.LEFT_HAND to From.SETTINGS
            DrivingSideMode.AUTO -> fromTheNetwork()
        }
        decidedMode = current
        readAtMs = clockMs()
        if (now != decided) {
            decided = now
            log("driving side: ${now.first.name} (${now.second.why})")
        }
        return now.first
    }

    private fun fromTheNetwork(): Pair<DrivingSide, From> {
        val network = read(networkCountry)
        if (network != null) return DrivingSide.forCountry(network) to From.NETWORK
        val sim = read(simCountry)
        return if (sim != null) DrivingSide.forCountry(sim) to From.SIM else DrivingSide.RIGHT_HAND to From.NONE
    }

    // A country reader that fails (no telephony on a tablet, a service that dies) is a country not known, never an error for the ride.
    private fun read(reader: () -> String?): String? = runCatching(reader).getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** How often the country is read again while the app runs: 10 minutes, a border is crossed far less often. */
        const val REFRESH_MS = 600_000L
    }
}
