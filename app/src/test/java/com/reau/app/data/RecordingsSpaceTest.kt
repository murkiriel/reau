package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The 200 MB ceiling held only for the logs; the recordings (`video`, `sessions`) went by age alone and, with
 * "Nunca" chosen, never (129 MB of video and 78 MB of sessions were measured on a device). Together they now
 * stay under a ceiling of their own, the oldest going first.
 */
class RecordingsSpaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var video: File
    private lateinit var sessions: File
    private val now = 1_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        video = tmp.newFolder("video")
        sessions = tmp.newFolder("sessions")
    }

    private fun file(dir: File, name: String, kb: Int, ageMs: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(kb * 1024)); setLastModified(now - ageMs) }

    private fun trim(capKb: Long) = RecordingsSpace.trim(listOf(video, sessions), capKb * 1024, now)

    @Test
    fun `under the ceiling nothing goes and nothing is said`() {
        file(video, "a.h264", 100, day)
        file(sessions, "b.aasession", 100, day)
        assertNull(trim(capKb = 300))
        assertEquals(2, video.listFiles()!!.size + sessions.listFiles()!!.size)
    }

    @Test
    fun `over it the oldest go first, across both folders, until the rest fits`() {
        file(sessions, "old.aasession", 100, 5 * day)
        file(video, "mid.h264", 100, 3 * day)
        file(sessions, "new.aasession", 100, day)
        val said = trim(capKb = 250)
        assertEquals("recordings space: 1 file removed (100 KB), 200 KB left of 250 KB", said)
        assertFalse(File(sessions, "old.aasession").exists())
        assertTrue(File(video, "mid.h264").exists() && File(sessions, "new.aasession").exists())
    }

    @Test
    fun `a video and its index go together, whole, never one without the other`() {
        file(video, "reau_1.h264", 100, 4 * day)
        file(video, "reau_1.csv", 10, 4 * day)
        file(video, "reau_2.h264", 100, day)
        file(video, "reau_2.csv", 10, day)
        val said = trim(capKb = 150)
        assertEquals("recordings space: 2 files removed (110 KB), 110 KB left of 150 KB", said)
        assertFalse(File(video, "reau_1.h264").exists() || File(video, "reau_1.csv").exists())
        assertTrue(File(video, "reau_2.h264").exists() && File(video, "reau_2.csv").exists())
    }

    @Test
    fun `a recording written in the last two minutes is in use and stays, even if that leaves the folders over`() {
        file(sessions, "live.aasession", 300, 30_000)
        file(video, "old.h264", 100, 2 * day)
        val said = trim(capKb = 100)
        assertEquals("recordings space: 1 file removed (100 KB), 300 KB left of 100 KB", said)
        assertTrue(File(sessions, "live.aasession").exists())
    }

    @Test
    fun `nothing to remove but a folder over, nothing is said`() {
        file(sessions, "live.aasession", 300, 1_000)
        assertNull(trim(capKb = 100))
    }

    @Test
    fun `a folder that does not exist is no problem, and other files are not touched`() {
        val none = File(tmp.root, "missing")
        file(video, "a.h264", 100, 5 * day)
        assertNull(RecordingsSpace.trim(listOf(none, video), 1_000_000, now))
        val said = RecordingsSpace.trim(listOf(none, video), 50 * 1024, now)
        assertEquals("recordings space: 1 file removed (100 KB), 0 KB left of 50 KB", said)
    }

    @Test
    fun `a recorder makes room in both recording folders, found beside its own`() {
        file(video, "v.h264", 100, 3 * day)
        file(sessions, "s.aasession", 100, 2 * day)
        val said = RecordingsSpace.makeRoom(sessions, capBytes = 120 * 1024, nowMs = now)
        assertEquals("recordings space: 1 file removed (100 KB), 100 KB left of 120 KB", said)
        assertFalse(File(video, "v.h264").exists())
    }

    @Test
    fun `the ceiling is 200 MB, and 1 GB in a debug build`() {
        assertEquals(200L * 1024 * 1024, RecordingsSpace.CAP_BYTES)
        assertEquals(200L * 1024 * 1024, RecordingsSpace.capBytes(debugBuild = false))
        assertEquals(1024L * 1024 * 1024, RecordingsSpace.capBytes(debugBuild = true))
    }

    private val mb = 1024L * 1024

    @Test
    fun `the recordings budget is 200 MB in a release and 1024 MB in a debug build, the logs' own besides`() {
        assertEquals(200 * mb, RecordingsSpace.capBytes(debugBuild = false))
        assertEquals(1024 * mb, RecordingsSpace.capBytes(debugBuild = true))
        assertEquals(200 * mb, DiagSpace.capBytes(debugBuild = false))
        assertEquals(1024 * mb, DiagSpace.capBytes(debugBuild = true))
    }

    @Test
    fun `a release keeps the ring of 100 MB whatever the free space`() {
        assertEquals(100 * mb, RecordingsSpace.ringBytes(freeBytes = 50_000 * mb, debugBuild = false))
        assertEquals(100 * mb, RecordingsSpace.ringBytes(freeBytes = 0, debugBuild = false))
    }

    @Test
    fun `a debug build's ring is three quarters of the budget, 768 MB, with room on the phone`() {
        assertEquals(768 * mb, RecordingsSpace.DEBUG_RING_BYTES)
        assertEquals(768 * mb, RecordingsSpace.ringBytes(freeBytes = 50_000 * mb, debugBuild = true))
        assertTrue("the ring and the rest of the budget fit in it", RecordingsSpace.DEBUG_RING_BYTES < RecordingsSpace.DEBUG_CAP_BYTES)
    }

    @Test
    fun `a debug build's ring shrinks to leave 512 MB free, and never below the release's 100 MB`() {
        assertEquals(768 * mb, RecordingsSpace.ringBytes(freeBytes = (512 + 768) * mb, debugBuild = true))
        assertEquals(500 * mb, RecordingsSpace.ringBytes(freeBytes = (512 + 500) * mb, debugBuild = true))
        assertEquals(100 * mb, RecordingsSpace.ringBytes(freeBytes = 300 * mb, debugBuild = true))
        assertEquals(100 * mb, RecordingsSpace.ringBytes(freeBytes = 0, debugBuild = true))
    }

    private fun room(capKb: Long, sessionsCapKb: Long) =
        RecordingsSpace.makeRoom(sessions, capBytes = capKb * 1024, nowMs = now, sessionsCapBytes = sessionsCapKb * 1024)

    private fun leftKb() = (video.listFiles()!!.toList() + sessions.listFiles()!!.toList()).sumOf { it.length() } / 1024

    @Test
    fun `under the share and the ceiling nothing goes and nothing is said`() {
        file(sessions, "s.aasession", 100, 3 * day)
        file(video, "v.h264", 100, 5 * day)
        assertNull(room(capKb = 300, sessionsCapKb = 150))
        assertEquals(2, video.listFiles()!!.size + sessions.listFiles()!!.size)
    }

    @Test
    fun `sessions over their share lose the oldest sessions, and the video stays though older than them`() {
        file(video, "v.h264", 100, 9 * day)
        file(sessions, "s1.aasession", 100, 5 * day)
        file(sessions, "s2.aasession", 100, 3 * day)
        file(sessions, "s3.aasession", 100, day)
        val said = room(capKb = 1000, sessionsCapKb = 200)
        assertEquals("recordings space: sessions over their share: 1 file removed (100 KB), 200 KB of sessions left of 200 KB", said)
        assertFalse(File(sessions, "s1.aasession").exists())
        assertTrue(File(sessions, "s2.aasession").exists() && File(sessions, "s3.aasession").exists())
        assertTrue("the video, the oldest of all, stays", File(video, "v.h264").exists())
    }

    @Test
    fun `sessions exactly at their share lose nothing`() {
        file(sessions, "s1.aasession", 100, 5 * day)
        file(sessions, "s2.aasession", 100, 3 * day)
        assertNull(room(capKb = 1000, sessionsCapKb = 200))
        assertEquals(2, sessions.listFiles()!!.size)
    }

    @Test
    fun `sessions in their share but the total over the ceiling, the old rule by age takes the oldest video`() {
        file(video, "v1.h264", 100, 9 * day)
        file(video, "v2.h264", 100, 2 * day)
        file(sessions, "s1.aasession", 100, 5 * day)
        file(sessions, "s2.aasession", 100, day)
        val said = room(capKb = 300, sessionsCapKb = 200)
        assertEquals("recordings space: 1 file removed (100 KB), 300 KB left of 300 KB", said)
        assertFalse(File(video, "v1.h264").exists())
        assertEquals(3, video.listFiles()!!.size + sessions.listFiles()!!.size)
    }

    @Test
    fun `both steps say their line, the share first and the total last, and what is left adds up`() {
        file(video, "v1.h264", 100, 9 * day)
        file(video, "v2.h264", 100, 2 * day)
        file(sessions, "s1.aasession", 100, 5 * day)
        file(sessions, "s2.aasession", 100, 4 * day)
        file(sessions, "s3.aasession", 100, day)
        val said = room(capKb = 250, sessionsCapKb = 200)!!.lines()
        assertEquals(
            listOf(
                "recordings space: sessions over their share: 1 file removed (100 KB), 200 KB of sessions left of 200 KB",
                "recordings space: 2 files removed (200 KB), 200 KB left of 250 KB",
            ),
            said,
        )
        assertEquals(200L, leftKb())
        assertFalse(File(video, "v1.h264").exists() || File(sessions, "s2.aasession").exists())
        assertTrue(File(video, "v2.h264").exists() && File(sessions, "s3.aasession").exists())
    }

    @Test
    fun `a session in use never goes, even over the share, and the other idle sessions go instead`() {
        file(sessions, "live.aasession", 300, 30_000)
        file(sessions, "idle.aasession", 100, 2 * day)
        file(video, "v.h264", 100, 3 * day)
        val said = room(capKb = 1000, sessionsCapKb = 200)
        assertEquals("recordings space: sessions over their share: 1 file removed (100 KB), 300 KB of sessions left of 200 KB", said)
        assertTrue(File(sessions, "live.aasession").exists())
        assertFalse(File(sessions, "idle.aasession").exists())
        assertTrue(File(video, "v.h264").exists())
    }

    @Test
    fun `a session in use alone over the share, nothing idle, nothing goes and nothing is said`() {
        file(sessions, "live.aasession", 300, 1_000)
        file(video, "v.h264", 50, 9 * day)
        assertNull(room(capKb = 1000, sessionsCapKb = 200))
        assertTrue(File(video, "v.h264").exists())
    }

    @Test
    fun `a session and its marks go together, whole, when the share is passed`() {
        file(sessions, "s1.aasession", 100, 5 * day)
        file(sessions, "s1.txt", 10, 5 * day)
        file(sessions, "s2.aasession", 100, day)
        val said = room(capKb = 1000, sessionsCapKb = 150)
        assertEquals("recordings space: sessions over their share: 2 files removed (110 KB), 100 KB of sessions left of 150 KB", said)
        assertTrue(File(sessions, "s2.aasession").exists())
    }

    @Test
    fun `the share is half of the ceiling, 512 MB in a debug build and 100 MB in a release`() {
        assertEquals(512 * mb, RecordingsSpace.sessionsCapBytes(debugBuild = true))
        assertEquals(100 * mb, RecordingsSpace.sessionsCapBytes(debugBuild = false))
        assertEquals(RecordingsSpace.capBytes(debugBuild = true) / 2, RecordingsSpace.sessionsCapBytes(debugBuild = true))
        assertTrue("the ring of one session, 768 MB, alone passes the share", RecordingsSpace.DEBUG_RING_BYTES > RecordingsSpace.sessionsCapBytes(debugBuild = true))
    }
}
