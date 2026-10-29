package com.reau.app.data

import java.io.File

/**
 * The recordings (`video/`, `sessions/`; optional, off by default in a release, on in a debug build) together stay under [capBytes], the oldest going
 * first. [DiagSpace] keeps the logs under their own ceiling (200 MB in a release, 1 GB in a debug build, as here); by age alone ([LogAge]) the recordings would, with "Nunca"
 * chosen, never go: about 35 MB for each Android Auto connection, hundreds of MB after a few.
 *
 * A video and its index (`x.h264`, `x.csv`) go together. A recording written to in the last [BUSY_MS] is in use and stays,
 * even if that leaves the folders over. Called when a recorder opens a file ([makeRoom]) and when the app starts.
 */
object RecordingsSpace {
    const val CAP_BYTES = 200L * 1024 * 1024

    /** A debug build (made to be read after a long test) keeps up to 1 GB of recordings, as of its logs. */
    const val DEBUG_CAP_BYTES = 1024L * 1024 * 1024

    /** The ceiling in this build: [DEBUG_CAP_BYTES] in a debug build, [CAP_BYTES] (200 MB) in a release, where the recordings are the rider's to switch on. */
    fun capBytes(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): Long = if (debugBuild) DEBUG_CAP_BYTES else CAP_BYTES
    private const val BUSY_MS = 2 * 60 * 1000L
    private const val KB = 1024L
    private val FOLDERS = listOf("video", "sessions")

    /** For a recorder about to open a file in [dir] (`video` or `sessions`): both folders, beside each other, under the ceiling. */
    fun makeRoom(dir: File, capBytes: Long = capBytes(), nowMs: Long = System.currentTimeMillis()): String? {
        val parent = dir.parentFile ?: return null
        return trim(FOLDERS.map { File(parent, it) }, capBytes, nowMs)
    }

    /** Fits [dirs] together under [capBytes]; what was removed, said, or null if nothing was. */
    fun trim(dirs: List<File>, capBytes: Long, nowMs: Long): String? {
        val groups = dirs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile }.groupBy { it.nameWithoutExtension }.values }
        var total = groups.sumOf { group -> group.sumOf { it.length() } }
        if (total <= capBytes) return null
        val idle = groups.filter { group -> group.all { nowMs - it.lastModified() > BUSY_MS } }
        var removed = 0
        var removedBytes = 0L
        for (group in idle.sortedBy { group -> group.maxOf { it.lastModified() } }) {
            if (total <= capBytes) break
            for (file in group) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    total -= size
                    removed++
                    removedBytes += size
                }
            }
        }
        val word = if (removed == 1) "file" else "files"
        return if (removed == 0) null else "recordings space: $removed $word removed (${removedBytes / KB} KB), ${total / KB} KB left of ${capBytes / KB} KB"
    }
}
