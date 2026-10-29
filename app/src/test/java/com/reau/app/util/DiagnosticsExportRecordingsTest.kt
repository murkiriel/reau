package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * If the rider wants, the debug recordings (`video/`, `sessions/`) go in the same zip, each in a folder of its name,
 * next to the logs.
 */
class DiagnosticsExportRecordingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(zip: File) = ZipFile(zip).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

    @Test
    fun `the logs at the top and each recording folder under its own name`() {
        val diag = tmp.newFolder("diag").apply { File(this, "ride-1.log").writeText("a"); File(this, "logcat-1.txt").writeText("b") }
        val video = tmp.newFolder("video").apply { File(this, "stream.h264").writeText("c") }
        val sessions = tmp.newFolder("sessions").apply { File(this, "s1.aap").writeText("d") }
        val dest = File(tmp.root, "out.zip")

        val count = DiagnosticsExport.zipDirs(listOf("" to diag, "video" to video, "sessions" to sessions), dest)

        assertEquals(4, count)
        assertEquals(listOf("logcat-1.txt", "ride-1.log", "video/stream.h264", "sessions/s1.aap"), names(dest))
    }

    @Test
    fun `a recording folder that does not exist is left out`() {
        val diag = tmp.newFolder("diag").apply { File(this, "ride-1.log").writeText("a") }
        val dest = File(tmp.root, "out.zip")
        assertEquals(1, DiagnosticsExport.zipDirs(listOf("" to diag, "video" to File(tmp.root, "none")), dest))
        assertEquals(listOf("ride-1.log"), names(dest))
    }

    /** Audit of 20/10/2026: up to 400 MB were put together without a look at the free space, always under one name. */
    private fun room(): Pair<File, File> = tmp.newFolder("shared") to tmp.newFolder("diag").apply { File(this, "ride-1.log").writeText("abcdef") }

    @Test
    fun `too little room throws before a byte is written, and the last zip stays`() {
        val (out, diag) = room()
        val old = File(out, "diagnostics-logs-20260101-000000-000.zip").apply { writeText("old") }
        try {
            DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { 1_000L })
            org.junit.Assert.fail("expected NotEnoughSpace")
        } catch (e: DiagnosticsExport.NotEnoughSpace) {
            assertEquals(1_000L, e.free)
        }
        assertEquals(listOf(old.name), out.list()!!.toList())
    }

    @Test
    fun `room enough, and the files plus a margin is what it asks for`() {
        val (out, diag) = room()
        var asked: File? = null
        val zip = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { asked = it; 17L * 1024 * 1024 })
        org.junit.Assert.assertNotNull(zip)
        assertEquals(out, asked)
        assertEquals(listOf("ride-1.log"), names(zip!!))
        try {
            DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { 16L * 1024 * 1024 + 5 }) // 6 bytes of files
            org.junit.Assert.fail("expected NotEnoughSpace")
        } catch (e: DiagnosticsExport.NotEnoughSpace) {
            assertEquals(16L * 1024 * 1024 + 6, e.needed)
        }
    }

    @Test
    fun `every export has a name of its own, and the earlier ones go only once the new one is whole`() {
        val (out, diag) = room()
        val first = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE }, nowMs = 1_000_000L)!!
        val second = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE }, nowMs = 2_000_000L)!!
        val sameMoment = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE }, nowMs = 2_000_000L)!!
        org.junit.Assert.assertNotEquals(first.name, second.name)
        org.junit.Assert.assertNotEquals("the same instant never overwrites", second.name, sameMoment.name)
        org.junit.Assert.assertTrue(sameMoment.exists())
        assertEquals("only the newest is kept, and no .part is left", listOf(sameMoment.name), out.list()!!.toList())
    }

    @Test
    fun `nothing to export is null, not an empty zip, and leaves no part file`() {
        val out = tmp.newFolder("shared")
        val empty = tmp.newFolder("empty")
        assertEquals(null, DiagnosticsExport.export(listOf("" to empty), out, freeBytes = { Long.MAX_VALUE }))
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }
}
