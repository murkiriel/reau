package com.reau.app.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What is kept on the phone for [ProjectionResume]: one mark, there while the rider has the projection on and gone when it is turned off, by the
 * rider or by itself. It holds the instant of the service's last sign of life (wall clock, epoch ms), whether the card mode was on, the instants of
 * the resumes already made, and when the rider took the app off the recent apps. Nothing personal: no name, no place, no network.
 *
 * A process killed by the system leaves the mark as it is, which is how the service that the system starts again finds out the projection was on. Every
 * write but the erasing is `apply` (in memory at once, on the disk a moment later); the erasing is `commit`, called on the worker, so that a Desligar that is
 * followed by the process going away does not leave a mark behind.
 */
internal class ProjectionResumeBook(private val prefs: SharedPreferences) {
    // When the mark's instant was last written by this process; the worker's beats read and write it.
    @Volatile private var lastBeatMs = 0L

    /** The rider switched the projection on at [nowMs]: a new mark, with no resumes counted. [card]: in the card mode. */
    fun begin(nowMs: Long, card: Boolean) {
        prefs.edit {
            putLong(SEEN, nowMs)
            putBoolean(CARD, card)
            remove(RESUMED)
            remove(REMOVED)
        }
        lastBeatMs = nowMs
    }

    /**
     * A sign of life at [nowMs] while the projection runs. Writes only when a mark is there (a beat after the erasing must not bring it back) and the last
     * write was [ProjectionResume.BEAT_EVERY_MS] ago or more; true when it wrote.
     */
    fun beat(nowMs: Long): Boolean {
        if (!prefs.contains(SEEN)) return false
        if (nowMs - lastBeatMs in 0 until ProjectionResume.BEAT_EVERY_MS) return false
        prefs.edit { putLong(SEEN, nowMs) }
        lastBeatMs = nowMs
        return true
    }

    /** The service was resumed at [nowMs]: the sign of life is now, and [resumedAtMs] are the resumes of the window, this one included. */
    fun recordResume(nowMs: Long, resumedAtMs: List<Long>) {
        prefs.edit {
            putLong(SEEN, nowMs)
            putString(RESUMED, resumedAtMs.joinToString(","))
        }
        lastBeatMs = nowMs
    }

    /** The rider took the app off the recent apps at [nowMs]; kept only when a mark is there. */
    fun taskRemoved(nowMs: Long) {
        if (prefs.contains(SEEN)) prefs.edit { putLong(REMOVED, nowMs) }
    }

    /** The mark as kept; null when there is none (or it holds no instant). */
    fun read(): ProjectionResume.Mark? {
        val seen = prefs.getLong(SEEN, 0L)
        if (seen <= 0L) return null
        val resumed = prefs.getString(RESUMED, null).orEmpty().split(",").mapNotNull { it.toLongOrNull() }
        return ProjectionResume.Mark(seen, prefs.getBoolean(CARD, false), resumed, prefs.getLong(REMOVED, 0L))
    }

    /** No projection left to resume: the mark and its count are erased. */
    fun erase() {
        prefs.edit(commit = true) { clear() }
        lastBeatMs = 0L
    }

    companion object {
        private const val NAME = "reau_projection_resume"
        private const val SEEN = "seen_at"
        private const val CARD = "card"
        private const val RESUMED = "resumed_at"
        private const val REMOVED = "task_removed_at"

        fun open(context: Context) = ProjectionResumeBook((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
    }
}
