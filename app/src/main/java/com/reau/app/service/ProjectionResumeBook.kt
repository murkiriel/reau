package com.reau.app.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What is kept on the phone for [ProjectionResume]: one mark, there while the rider has the projection on and gone when it is turned off, by the
 * rider or by itself. It holds the instant of the service's last sign of life (wall clock, epoch ms), whether the card mode was on, the instants of
 * the resumes already made, when the rider took the app off the recent apps, and the last instant the dash was connected. Nothing personal: no name, no place, no network.
 *
 * A process killed by the system leaves the mark as it is, which is how the service that the system starts again finds out the projection was on. The beats and the
 * start are `apply` (in memory at once, on the disk a moment later); the erasing, the count of a resume, the removal from the recent apps and the low-battery notice
 * given are `commit` (26/11/2026), so that a Desligar, or a manager that ends the process a few milliseconds after the rider's gesture, does not leave the disk with
 * the mark as it was.
 *
 * It also keeps that the low-battery notice was already given in this ride ([batteryTold]): the mark outlives a death by the system, so the projection resumed after it
 * does not tell the rider again (the owner asked for "once per trip", 26/11/2026); the Desligar erases it with the mark and a new Ligar starts without it.
 */
internal class ProjectionResumeBook(private val prefs: SharedPreferences) {
    // When the mark's instant was last written by this process; the worker's beats read and write it.
    @Volatile private var lastBeatMs = 0L

    // The last instant the dash was seen connected, by this process; every beat renews it, and the write that the minute allows keeps it (the dash's own instant is
    // never more than that minute late on the disk).
    @Volatile private var lastDashUpMs = 0L

    /** The rider switched the projection on at [nowMs]: a new mark, with no resumes counted. [card]: in the card mode. */
    fun begin(nowMs: Long, card: Boolean) {
        prefs.edit {
            putLong(SEEN, nowMs)
            putBoolean(CARD, card)
            // The dash is waited for from now: a mark made at Ligar counts as "the dash was here" until the beat says otherwise.
            putLong(DASH_UP, nowMs)
            remove(RESUMED)
            remove(REMOVED)
            remove(BATTERY_TOLD)
        }
        lastBeatMs = nowMs
        lastDashUpMs = nowMs
    }

    /**
     * A sign of life at [nowMs] while the projection runs; [dashUp]: the dash is connected at this instant. Writes only when a mark is there (a beat after the
     * erasing must not bring it back) and the last write was [ProjectionResume.BEAT_EVERY_MS] ago or more; true when it wrote. What it writes is the instant and the
     * last instant the dash was connected (renewed by every call with [dashUp], written with the next write): [ProjectionResume.decide] does not resume a
     * projection whose dash was gone for long at the last sign of life.
     */
    fun beat(nowMs: Long, dashUp: Boolean): Boolean {
        if (!prefs.contains(SEEN)) return false
        if (dashUp) lastDashUpMs = nowMs
        if (nowMs - lastBeatMs in 0 until ProjectionResume.BEAT_EVERY_MS) return false
        prefs.edit {
            putLong(SEEN, nowMs)
            // A process that has not seen the dash since it began (the resume's first minute) leaves the instant the mark has.
            if (lastDashUpMs > 0) putLong(DASH_UP, lastDashUpMs)
        }
        lastBeatMs = nowMs
        return true
    }

    /**
     * The service was resumed at [nowMs]: the sign of life is now, the dash is waited for from now (a resume that is killed again within two minutes is resumed
     * again, the dash having had no time to come), and [resumedAtMs] are the resumes of the window, this one included. `commit`, not `apply` (25/11/2026): the
     * count is what stops a process that dies again at once, so it is on the disk before the call returns, and a kill a moment later cannot lose it.
     */
    fun recordResume(nowMs: Long, resumedAtMs: List<Long>) {
        prefs.edit(commit = true) {
            putLong(SEEN, nowMs)
            putLong(DASH_UP, nowMs)
            putString(RESUMED, resumedAtMs.joinToString(","))
        }
        lastBeatMs = nowMs
        lastDashUpMs = nowMs
    }

    /**
     * The rider took the app off the recent apps at [nowMs]; kept only when a mark is there. `commit`, not `apply` (26/11/2026): a maker's manager may end the process
     * milliseconds after this gesture, and a mark that lost the removal would resume the projection against what the rider just did.
     */
    fun taskRemoved(nowMs: Long) {
        if (prefs.contains(SEEN)) prefs.edit(commit = true) { putLong(REMOVED, nowMs) }
    }

    /** Whether the low-battery notice was already given in this ride (this mark's); false without a mark. */
    fun batteryTold(): Boolean = prefs.contains(SEEN) && prefs.getBoolean(BATTERY_TOLD, false)

    /**
     * The low-battery notice was just given: kept with the mark, so a resume after a death by the system does not give it again. `commit`: the system may close the
     * process again within seconds (25/11/2026: twice in 40 s). Only when a mark is there, so that a late look after the erasing does not bring a key back.
     */
    fun recordBatteryTold() {
        if (prefs.contains(SEEN)) prefs.edit(commit = true) { putBoolean(BATTERY_TOLD, true) }
    }

    /** The rider opened the app again: the removal from the recent apps no longer stands for this ride. Only writes when there is one to take away. */
    fun reopened() {
        if (prefs.contains(REMOVED)) prefs.edit { remove(REMOVED) }
    }

    /** The mark as kept; null when there is none (or it holds no instant). */
    fun read(): ProjectionResume.Mark? {
        val seen = prefs.getLong(SEEN, 0L)
        if (seen <= 0L) return null
        val resumed = prefs.getString(RESUMED, null).orEmpty().split(",").mapNotNull { it.toLongOrNull() }
        return ProjectionResume.Mark(seen, prefs.getBoolean(CARD, false), resumed, prefs.getLong(REMOVED, 0L), prefs.getLong(DASH_UP, 0L))
    }

    /** No projection left to resume: the mark and its count are erased. */
    fun erase() {
        prefs.edit(commit = true) { clear() }
        lastBeatMs = 0L
        lastDashUpMs = 0L
    }

    companion object {
        private const val NAME = "reau_projection_resume"
        private const val SEEN = "seen_at"
        private const val CARD = "card"
        private const val RESUMED = "resumed_at"
        private const val REMOVED = "task_removed_at"
        private const val DASH_UP = "dash_up_at"
        private const val BATTERY_TOLD = "battery_told"

        fun open(context: Context) = ProjectionResumeBook((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
    }
}
