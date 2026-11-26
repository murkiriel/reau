package com.reau.app.service

/**
 * When the rider is told the phone's battery is nearly out (plan 35, step E, 26/11/2026). Ride of 25/11/2026: the phone went from 12 % to 4 % in about
 * 2 minutes at 3.0 V with the projection on and the bike not charging it, and the system closed REau twice. Nothing in the app said the phone was about
 * to die; the rider found out when the picture went.
 *
 * Two decisions, pure, from the same looks (every 5 s: the battery percentage and the charging state).
 *
 * The phone's notice: [offer] says `true` once, the first time the battery is at [lowPct] or under with the charger out; never again in this trip, however much the
 * percentage wobbles around the limit (10, 11, 10). A projection that starts already under the limit is told at its first look. A look that does not know (a null or
 * negative percentage, an unknown charging state) says nothing and changes nothing, and a charger in says nothing and leaves the watch armed (it is the charger out that is the
 * danger). One watch per projection: a new projection is a new object, armed, as [BatteryHeatWatch]. A projection resumed after a death by the system is a new object too, but
 * the same ride: it is made with [alreadyTold] true when the ride's mark says the notice was given, so the rider hears it once per trip, not once per process (26/11/2026, the
 * review of the plan 35: with 8 % of battery and two deaths in 40 s the notice would have sounded again).
 *
 * The dash's pill ([com.reau.app.dash.BatteryPill]): the owner's decision of 26/11/2026 is that it alternates (a minute on, a minute out) for as long as the battery is low,
 * not once. [pillStep] says what to do with it at each look: [PillStep.START] when the battery is at [lowPct] or under with no charger and the dash is up; [PillStep.KEEP] while
 * that holds (the level in its words changes); a stop, with its reason, when the charger came in, the battery went above the limit or the dash went; nothing otherwise. It does
 * not depend on [alreadyTold]: a resumed projection has the notice told already, and the pill has still to be seen on the dash. A battery that falls again after a stop (charger out, under the
 * limit) starts it again, without a second notice. What the last look that knew said is what is kept through the looks that do not know.
 */
class BatteryLowWatch(private val lowPct: Int = LOW_PCT, alreadyTold: Boolean = false) {
    private var told = alreadyTold
    private var low = false
    private var chargerIn = false
    private var pillOn = false

    /** What to do with the dash's pill at a look. */
    enum class PillStep(val reason: String) {
        NONE(""),
        START(""),
        KEEP(""),
        STOP_CHARGER("charger in"),
        STOP_LEVEL("battery above the limit"),
        STOP_DASH("dash gone, it starts again when the dash is back"),
        STOP_END("projection over"),
        ;

        /** The pill is to be asked for (or have its words changed). */
        val asks: Boolean get() = this == START || this == KEEP

        /** The pill is to be taken off. */
        val stops: Boolean get() = reason.isNotEmpty()
    }

    /** The look at [batteryPct] with the charger [charging] (null: not known); true when the rider is to be told now (the phone's notice, once). */
    fun offer(batteryPct: Int?, charging: Boolean?): Boolean {
        val due = !told && batteryPct != null && batteryPct in 0..lowPct && charging == false
        follow(batteryPct, charging)
        if (due) {
            told = true
        }
        return due
    }

    /** What the look knows of the pill's condition: a known charger in, or a known level above the limit, ends it; a known level under it with the charger out begins it. */
    private fun follow(batteryPct: Int?, charging: Boolean?) {
        val level = batteryPct?.takeIf { it >= 0 }
        when {
            charging == true -> {
                low = false
                chargerIn = true
            }
            level != null && level > lowPct -> {
                low = false
                chargerIn = false
            }
            level != null && charging == false -> low = true
        }
    }

    /** Whether the last look that knew said the battery is low with no charger: the condition the pill alternates for. */
    val lowNow: Boolean get() = low

    /** What to do with the pill at this look, once [offer] has been given it: [dashUp] false means the dash is not connected (a pill drawn then would run its minute unseen). */
    fun pillStep(dashUp: Boolean): PillStep = when {
        low && dashUp -> if (pillOn) PillStep.KEEP else PillStep.START.also { pillOn = true }
        !pillOn -> PillStep.NONE
        else -> {
            pillOn = false
            when {
                low -> PillStep.STOP_DASH
                chargerIn -> PillStep.STOP_CHARGER
                else -> PillStep.STOP_LEVEL
            }
        }
    }

    /** The projection is over: [PillStep.STOP_END] when the pill was alternating (to be taken off and said), else [PillStep.NONE]. */
    fun pillEnds(): PillStep {
        if (!pillOn) return PillStep.NONE
        pillOn = false
        return PillStep.STOP_END
    }

    /**
     * The ride-log line for the end of the projection, or null when the pill was not alternating: there is nothing to say then (review of the plan 34, A1, 26/11/2026:
     * [pillEnds] answers [PillStep.NONE], which is not null, and a line with no reason was written at the end of every projection, battery full or not).
     */
    fun endLine(): String? = pillEnds().takeIf { it.stops }?.let { "battery pill alternation stops: ${it.reason}" }

    companion object {
        /** The battery percentage the rider is told at, or under it (the owner's decision of 26/11/2026). */
        const val LOW_PCT = 10
    }
}
