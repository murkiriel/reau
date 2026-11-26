package com.reau.app.service

/**
 * When the rider is told the phone's battery is nearly out (plan 35, step E, 26/11/2026). Ride of 25/11/2026: the phone went from 12 % to 4 % in about
 * 2 minutes at 3.0 V with the projection on and the bike not charging it, and the system closed REau twice. Nothing in the app said the phone was about
 * to die; the rider found out when the picture went.
 *
 * The decision, pure: [offer] is given the battery percentage and the charging state of each look (every 5 s) and says `true` once, the first time the
 * battery is at [lowPct] or under with the charger out; never again in this projection, however much the percentage wobbles around the limit (10, 11, 10).
 * A projection that starts already under the limit is told at its first look. A look that does not know (a null or negative percentage, an unknown
 * charging state) says nothing and changes nothing, and a charger in says nothing and leaves the watch armed (it is the charger out that is the danger).
 * One watch per projection: a new projection is a new object, armed, as [BatteryHeatWatch].
 */
class BatteryLowWatch(private val lowPct: Int = LOW_PCT) {
    private var told = false

    /** The look at [batteryPct] with the charger [charging] (null: not known); true when the rider is to be told now. */
    fun offer(batteryPct: Int?, charging: Boolean?): Boolean {
        val due = !told && batteryPct != null && batteryPct in 0..lowPct && charging == false
        if (due) {
            told = true
            pillPending = true
        }
        return due
    }

    /**
     * The dash's pill of the notice still to be shown: set when the notice is due, kept while the dash is not connected (a pill drawn then would run its 5 s with
     * nobody seeing it: 26/11/2026), and taken once by [pillDue].
     */
    var pillPending = false
        private set

    /** Whether the pill is to be asked for now: once, the first time it is pending and the dash is connected ([dashUp]). */
    fun pillDue(dashUp: Boolean): Boolean {
        if (!pillPending || !dashUp) return false
        pillPending = false
        return true
    }

    companion object {
        /** The battery percentage the rider is told at, or under it (the owner's decision of 26/11/2026). */
        const val LOW_PCT = 10
    }
}
