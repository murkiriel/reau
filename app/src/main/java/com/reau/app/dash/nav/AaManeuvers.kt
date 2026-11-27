package com.reau.app.dash.nav

import com.reau.app.aa.NavGuidance
import com.reau.app.dash.protocol.DashCommands
import kotlin.math.roundToInt

/**
 * Android Auto's turn-by-turn guidance as the dash's own nav bubble shows it.
 *
 * The maneuver type is Android Auto's NavigationManeuver.NavigationType wire number; each one becomes the dash glyph
 * of the catalog ([DashManeuverGlyphs], the glyph table observed on the dash, still to be checked
 * on the bike): turns by sharpness and side, ramps on and off with bytes of their own (the start of the route too), keep-left/right and forks as the Y fork, merges
 * through the catalog's own rule, roundabouts with their exit (in the direction Android Auto says; when it does not, the way the
 * driving side goes: counter-clockwise in right-hand traffic, clockwise in left-hand), ferries, arrivals. Anything else is the hidden bubble here, which the session never
 * sends while guiding (DashSession.ringManeuver keeps the last drawable maneuver); rerouting shows the spinning
 * compass. Distances under a kilometre go in metres, from there in tenths of a kilometre; with the dash in miles ([distance]'s imperial) in feet
 * up to 550 of them and then in tenths of a mile.
 */
object AaManeuvers {
    /** What the dash's nav packet carries ([com.reau.app.dash.DashSession.updateNavInfo]). */
    data class DashNav(
        val maneuver: Int, val primaryDist: Int, val primaryUnit: Int, val totalDist: Int, val totalUnit: Int, val eta: String?,
        /** The street of the next maneuver, for the dash only; blank when Android Auto does not say it. */
        val road: String = "",
        /** The time left in seconds, [DashCommands.REMAINING_OMIT] when Android Auto does not say it. */
        val remainingSeconds: Long = DashCommands.REMAINING_OMIT,
        /** The glyph of the maneuver after the next, [DashCommands.NEXT_MANEUVER_NONE] for none. */
        val next: Int = DashCommands.NEXT_MANEUVER_NONE,
    )

    /** The scanned catalog's ferry and railway-crossing glyphs ([DashManeuverGlyphs.describe] 0x3E, 0x3F). */
    private const val FERRY = 0x3E
    private const val RAIL = 0x3F
    private const val ONE_KM = 1_000

    /** With the dash in miles, up to this many feet the distance goes in feet, above it in tenths of a mile. */
    private const val FEET_LIMIT = 550
    private const val FIELD_MAX = 0xFFFF
    private val CLOCK = Regex("""\d{1,2}:\d{2}""")

    // Android Auto's NavigationType numbers, by what the dash draws for them.
    private val BY_TYPE: Map<Int, Int> = buildMap {
        for (t in listOf(2, 29, 36)) put(t, DashManeuverGlyphs.STRAIGHT) // name change, merge (no side), straight
        put(1, DashManeuverGlyphs.DEPART) // the start of the route: the pin alone
        put(3, DashManeuverGlyphs.FORK_LEFT)
        put(4, DashManeuverGlyphs.FORK_RIGHT)
        put(25, DashManeuverGlyphs.FORK_LEFT)
        put(26, DashManeuverGlyphs.FORK_RIGHT)
        // Turns (5..10): slight, normal, sharp, each left then right.
        val turns = listOf(
            DashManeuverGlyphs.SLIGHT_LEFT, DashManeuverGlyphs.SLIGHT_RIGHT, DashManeuverGlyphs.TURN_LEFT,
            DashManeuverGlyphs.TURN_RIGHT, DashManeuverGlyphs.SHARP_LEFT, DashManeuverGlyphs.SHARP_RIGHT,
        )
        turns.forEachIndexed { i, glyph -> put(5 + i, glyph) }
        // Ramps on (13..18), the same order, with bytes of their own (not yet seen on the bike).
        listOf(
            DashManeuverGlyphs.ON_RAMP_SLIGHT_LEFT, DashManeuverGlyphs.ON_RAMP_SLIGHT_RIGHT, DashManeuverGlyphs.ON_RAMP_LEFT,
            DashManeuverGlyphs.ON_RAMP_RIGHT, DashManeuverGlyphs.ON_RAMP_SHARP_LEFT, DashManeuverGlyphs.ON_RAMP_SHARP_RIGHT,
        ).forEachIndexed { i, glyph -> put(13 + i, glyph) }
        // The slight ramps off (21 left, 22 right) are the "bear off"; the normal ones (23, 24) are the side road that leaves: see below.
        put(21, DashManeuverGlyphs.OFF_RAMP_SLIGHT_LEFT)
        put(22, DashManeuverGlyphs.OFF_RAMP_SLIGHT_RIGHT)
        put(23, DashManeuverGlyphs.OFF_RAMP_LEFT)
        put(24, DashManeuverGlyphs.OFF_RAMP_RIGHT)
        put(11, DashManeuverGlyphs.UTURN_LEFT)
        put(12, DashManeuverGlyphs.UTURN_RIGHT)
        put(19, DashManeuverGlyphs.UTURN_LEFT)
        put(20, DashManeuverGlyphs.UTURN_RIGHT)
        put(27, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_LEFT))
        put(28, DashManeuverGlyphs.codeFor(ManeuverType.MERGE_RIGHT))
        put(37, FERRY)
        put(38, RAIL)
        put(39, DashManeuverGlyphs.ARRIVE)
        put(40, DashManeuverGlyphs.ARRIVE)
        put(41, DashManeuverGlyphs.ARRIVE_LEFT)
        put(42, DashManeuverGlyphs.ARRIVE_RIGHT)
    }
    // Android Auto says the direction of a roundabout in 32..35; 30 and 31 (enter, exit) do not, and follow the driving side.
    private val ROUNDABOUT_CCW = setOf(34, 35)
    private val ROUNDABOUT_CW = setOf(32, 33)
    private val ROUNDABOUT_NO_DIRECTION = setOf(30, 31)

    /**
     * Whether [navigationType] is one this class draws as something: a number of [BY_TYPE] or a roundabout's. The others (the unknown 0 of a
     * stretch with no turn ahead, 45 seen at the end of a route, 43, 44...) end in the hidden bubble.
     */
    fun knowsType(navigationType: Int): Boolean = navigationType in BY_TYPE || navigationType in ROUNDABOUT_CCW || navigationType in ROUNDABOUT_CW ||
            navigationType in ROUNDABOUT_NO_DIRECTION

    /**
     * The glyph for one Android Auto maneuver number. A roundabout whose direction Android Auto states (32..35) keeps it whatever [drivingSide] is;
     * only the ones it does not state (30 and 31) go round the way [drivingSide] says (clockwise in left-hand traffic).
     */
    fun glyphFor(
        navigationType: Int, roundaboutExit: Int, rerouting: Boolean = false, drivingSide: DrivingSide = DrivingSide.RIGHT_HAND,
    ): Int = when {
        rerouting -> DashManeuverGlyphs.RECALCULATING
        navigationType in ROUNDABOUT_CCW -> DashManeuverGlyphs.roundabout(roundaboutExit, clockwise = false)
        navigationType in ROUNDABOUT_CW -> DashManeuverGlyphs.roundabout(roundaboutExit, clockwise = true)
        navigationType in ROUNDABOUT_NO_DIRECTION -> DashManeuverGlyphs.roundabout(roundaboutExit, drivingSide.roundaboutClockwise)
        else -> BY_TYPE[navigationType] ?: DashManeuverGlyphs.HIDDEN_BUBBLE
    }

    /**
     * [meters] as the dash's (value, unit); unknown (negative) is 0 m, or 0 ft when [imperial]. Metric: metres under a kilometre, from there
     * tenths of a kilometre. Imperial (the dash in miles): feet up to 550 of them, above that tenths of a mile.
     */
    fun distance(meters: Int, imperial: Boolean = false): Pair<Int, Int> = when {
        imperial -> imperialDistance(meters)
        meters < 0 -> 0 to DashCommands.NAV_UNIT_METERS
        meters < ONE_KM -> meters to DashCommands.NAV_UNIT_METERS
        else -> (meters / DashCommands.METRES_PER_KM_TENTH).roundToInt().coerceAtMost(FIELD_MAX) to DashCommands.NAV_UNIT_KM_TENTHS
    }

    private fun imperialDistance(meters: Int): Pair<Int, Int> {
        val feet = meters.coerceAtLeast(0) * DashCommands.FEET_PER_METRE
        return if (feet <= FEET_LIMIT) {
            feet.roundToInt() to DashCommands.NAV_UNIT_FEET
        } else {
            (meters / DashCommands.METRES_PER_MILE_TENTH).roundToInt().coerceAtMost(FIELD_MAX) to DashCommands.NAV_UNIT_MILE_TENTHS
        }
    }

    private const val SECONDS_PER_DAY = 86_400L
    private const val JUST_PASSED_S = 120L
    private const val HALF_MINUTE_S = 30L

    /**
     * The time left from the arrival time text ("08:01", or "2:35 PM" in 12 hours) and [now]: Waze never says it in seconds. The text is
     * to the minute, so the arrival is taken at the middle of its minute ("08:01" is 08:01:30) and the answer is rounded to whole
     * minutes: from hh:mm:00 the dash said 1 min less than Waze in all 6 checks in use (11 for 12, ..., 1
     * for 2), from the middle it says what Waze says in all 6. An arrival that has just passed is 0, not a day; a text that is no time
     * is null.
     */
    fun remainingFromEta(eta: String, now: java.time.LocalTime): Long? {
        val digits = DashCommands.etaDigits(eta) ?: return null
        val arrival = digits.substring(0, 2).toLong() * 3_600 + digits.substring(2).toLong() * 60 + HALF_MINUTE_S
        val ahead = ((arrival - now.toSecondOfDay()) % SECONDS_PER_DAY + SECONDS_PER_DAY) % SECONDS_PER_DAY
        if (ahead > SECONDS_PER_DAY - JUST_PASSED_S) return 0L
        return (ahead + HALF_MINUTE_S) / 60 * 60
    }

    /**
     * The dash's nav figures for [guidance]; null when there is no guidance. [noNetwork]: the phone has been
     * without a network for a while, which turns the recalculating compass into the lost-signal icon; a real turn is never hidden.
     * [drivingSide]: the side of the road, for the roundabouts Android Auto gives no direction to (see [glyphFor]).
     */
    fun toDash(
        guidance: NavGuidance, noNetwork: Boolean = false, now: java.time.LocalTime = java.time.LocalTime.now(), imperial: Boolean = false,
        drivingSide: DrivingSide = DrivingSide.RIGHT_HAND,
    ): DashNav? {
        if (!guidance.active) return null
        val (primary, primaryUnit) = distance(guidance.distanceToManeuverMeters, imperial)
        val (total, totalUnit) = distance(guidance.distanceRemainingMeters, imperial)
        val glyph = if (guidance.rerouting && noNetwork) {
            DashManeuverGlyphs.SIGNAL_LOST
        } else {
            glyphFor(guidance.maneuverType, guidance.roundaboutExitNumber, guidance.rerouting, drivingSide)
        }
        val next = glyphFor(guidance.nextManeuverType, guidance.nextRoundaboutExit, drivingSide = drivingSide)
            .takeIf { guidance.nextManeuverType >= 0 && DashManeuverGlyphs.isVisible(it) } ?: DashCommands.NEXT_MANEUVER_NONE
        return DashNav(
            glyph,
            primary, primaryUnit, total, totalUnit,
            // The arrival as a 24-hour clock: a 12-hour one ("2:35 PM") is turned into "14:35"; one already in 24 hours goes as it was said.
            guidance.estimatedTimeAtArrival.let { eta -> eta.takeIf { CLOCK.matches(it) } ?: DashCommands.clock24(eta) },
            guidance.road,
            guidance.timeToArrivalSeconds.takeIf { it >= 0 } ?: remainingFromEta(guidance.estimatedTimeAtArrival, now) ?: DashCommands.REMAINING_OMIT,
            next,
        )
    }
}
