package com.reau.app.data

import java.io.File

/**
 * The recordings (`video/`, `sessions/`; optional, off by default in a release, on in a debug build) together stay under [capBytes], the oldest going
 * first: 200 MB in a release, 1024 MB in a debug build, the video sent to the dash and the session recordings (and the copies a mark keeps) counted together.
 * [DiagSpace] keeps the logs under their own ceiling (200 MB in a release, 1024 MB in a debug build): the two budgets are independent. By age alone ([LogAge])
 * the recordings would, with "Nunca" chosen, never go: about 35 MB for each Android Auto connection, hundreds of MB after a few.
 *
 * A video and its index (`x.h264`, `x.csv`) go together. A recording written to in the last [BUSY_MS] is in use and stays,
 * even if that leaves the folders over. The sessions have a share of their own, half of the ceiling ([sessionsCapBytes]): when `sessions/` passes it, its
 * oldest go first, before the video sent to the dash, and only then does the ceiling of the two folders together apply, by age. Called when a recorder opens a file ([makeRoom]) and when the app starts.
 */
object RecordingsSpace {
    const val CAP_BYTES = 200L * 1024 * 1024

    /** A debug build (made to be read after a long test) keeps up to 1024 MB of recordings, and 1024 MB of logs besides. */
    const val DEBUG_CAP_BYTES = 1024L * 1024 * 1024

    /**
     * The ring of one Android Auto session recording ([com.reau.app.aa.SessionRecorder]): 100 MB in a release. In a debug build, a long ride has to
     * survive (the 100 MB ring overwrote 1 h 30 of the ride of 01/11/2026), so it is three quarters of the budget: 768 MB, about an hour and a half
     * of Android Auto, and the video sent to the dash, which rolls in parts, takes the rest, the oldest parts going first.
     */
    const val RING_BYTES = 100L * 1024 * 1024
    const val DEBUG_RING_BYTES = DEBUG_CAP_BYTES / 4 * 3

    /** The free space the ring leaves on the phone: a recording never takes the last of it. */
    const val RING_FREE_RESERVE_BYTES = 512L * 1024 * 1024

    /**
     * The ring of a session recording opened now, with [freeBytes] free on the phone: [RING_BYTES] in a release; in a debug build [DEBUG_RING_BYTES],
     * or less when the phone would be left with less than [RING_FREE_RESERVE_BYTES] (never below [RING_BYTES]).
     */
    fun ringBytes(freeBytes: Long, debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): Long =
        if (!debugBuild) RING_BYTES else (freeBytes - RING_FREE_RESERVE_BYTES).coerceIn(RING_BYTES, DEBUG_RING_BYTES)

    /** The ceiling in this build: [DEBUG_CAP_BYTES] in a debug build, [CAP_BYTES] (200 MB) in a release, where the recordings are the rider's to switch on. */
    fun capBytes(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): Long = if (debugBuild) DEBUG_CAP_BYTES else CAP_BYTES
    private const val BUSY_MS = 2 * 60 * 1000L
    private const val KB = 1024L
    private val FOLDERS = listOf("video", "sessions")

    /**
     * The share of the Android Auto session recordings (`sessions/`) in the ceiling: half of [capBytes], 512 MB in a debug build, 100 MB in a release.
     * The sessions are about three quarters of what is recorded (about 240 MB for an hour of ride, against 80 MB of video sent to the dash), so without
     * a share of their own they filled the ring in about 3 h of projection and took the video of whole days with them.
     */
    fun sessionsCapBytes(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): Long = capBytes(debugBuild) / 2

    /**
     * For a recorder about to open a file in [dir] (`video` or `sessions`): the sessions under their share ([sessionsCapBytes]), then both folders,
     * beside each other, under the ceiling. What was removed, said, one line for each step (the share first, the total last), or null if nothing was.
     */
    fun makeRoom(
        dir: File,
        capBytes: Long = capBytes(),
        nowMs: Long = System.currentTimeMillis(),
        sessionsCapBytes: Long = sessionsCapBytes(),
    ): String? {
        val parent = dir.parentFile ?: return null
        val said = listOfNotNull(
            trimSessions(File(parent, "sessions"), sessionsCapBytes, nowMs),
            trim(FOLDERS.map { File(parent, it) }, capBytes, nowMs),
        )
        return said.joinToString("\n").ifEmpty { null }
    }

    /** First step: [sessions] over [sessionsCapBytes] loses its oldest idle groups until it fits (or none is idle); what was removed, said, or null. */
    fun trimSessions(sessions: File, sessionsCapBytes: Long, nowMs: Long): String? {
        val groups = groupsOf(sessions)
        val total = groups.sumOf { group -> group.sumOf { it.length() } }
        if (total <= sessionsCapBytes) return null
        val (removed, removedBytes) = removeOldestIdle(groups, total, sessionsCapBytes, nowMs)
        val word = if (removed == 1) "file" else "files"
        val left = (total - removedBytes) / KB
        val share = sessionsCapBytes / KB
        return if (removed == 0) null else
            "recordings space: sessions over their share: $removed $word removed (${removedBytes / KB} KB), $left KB of sessions left of $share KB"
    }

    /** Fits [dirs] together under [capBytes]; what was removed, said, or null if nothing was. */
    fun trim(dirs: List<File>, capBytes: Long, nowMs: Long): String? {
        val groups = dirs.flatMap { groupsOf(it) }
        val total = groups.sumOf { group -> group.sumOf { it.length() } }
        if (total <= capBytes) return null
        val (removed, removedBytes) = removeOldestIdle(groups, total, capBytes, nowMs)
        val word = if (removed == 1) "file" else "files"
        val left = (total - removedBytes) / KB
        return if (removed == 0) null else "recordings space: $removed $word removed (${removedBytes / KB} KB), $left KB left of ${capBytes / KB} KB"
    }

    private fun groupsOf(dir: File): List<List<File>> =
        dir.listFiles().orEmpty().filter { it.isFile }.groupBy { it.nameWithoutExtension }.values.toList()

    /** Removes the oldest idle [groups] (none written to in the last [BUSY_MS]) until [total] fits [limit]; how many files went, and their bytes. */
    private fun removeOldestIdle(groups: List<List<File>>, total: Long, limit: Long, nowMs: Long): Pair<Int, Long> {
        val idle = groups.filter { group -> group.all { nowMs - it.lastModified() > BUSY_MS } }
        var removed = 0
        var removedBytes = 0L
        for (group in idle.sortedBy { group -> group.maxOf { it.lastModified() } }) {
            if (total - removedBytes <= limit) break
            for (file in group) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    removed++
                    removedBytes += size
                }
            }
        }
        return removed to removedBytes
    }
}
