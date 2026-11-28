package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * The receiver's answer to the rider's mark ([MarkFreezer.keep]): the files are opened at once, in the caller's thread, and the bytes are copied
 * only when the executor it was given runs them, so the read thread never waits on a copy. Before this test the order was proved by reading the
 * receiver's source (MarkFreezeIsWiredTest); what the copy holds is MarkFreezerTest's.
 */
class MarkKeepTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sessions get() = File(tmp.root, "sessions")
    private val said = mutableListOf<String>()
    private val queued = mutableListOf<Runnable>()

    /** An executor in the test's hand: nothing runs until [runQueued]. */
    private val io = Executor { queued += it }

    private fun runQueued() = queued.toList().also { queued.clear() }.forEach { it.run() }

    private fun recorder(): SessionRecorder {
        val r = SessionRecorder(sessions, "reau_20261101_184137_902", 100_000, { said += it }, { 1_762_024_551_000L }, ring = true)
        repeat(5) { r.write(AapMessage(Channel.ID_VID, 0x0B.toByte(), 1, 2, 12, ByteArray(12) { b -> (it + b).toByte() })) }
        return r
    }

    private fun keeper() = MarkFreezer(
        sessions, { said += it }, { 1_762_024_551_000L }, TimeZone.getTimeZone("UTC"),
        MarkFreezer.Limits(3, Long.MAX_VALUE, reserveBytes = 0), freeBytes = { Long.MAX_VALUE },
    )

    private fun kept() = sessions.listFiles().orEmpty().map { it.name }.filter { MarkFreezer.isFrozenName(it) }

    @Test
    fun `the files are opened now and the bytes are copied only when the executor runs it`() {
        val r = recorder()
        assertTrue(MarkFreezer.keep(keeper(), r, io, mark = 1, log = { said += it }))
        assertEquals("the copy waits for the executor: not on the caller's thread", emptyList<String>(), kept())
        assertEquals("one task was handed over", 1, queued.size)
        runQueued()
        assertEquals(listOf("reau_20251101_191551_marca.aasession"), kept())
        r.close()
    }

    @Test
    fun `with the option off nothing is kept and nothing is handed to the executor`() {
        val r = recorder()
        assertFalse(MarkFreezer.keep(null, r, io, mark = 1, log = { said += it }))
        assertEquals(0, queued.size)
        assertEquals(emptyList<String>(), kept())
        r.close()
    }

    @Test
    fun `with no session recording open the log says so and nothing is handed to the executor`() {
        val lines = mutableListOf<String>()
        assertFalse(MarkFreezer.keep(keeper(), null, io, mark = 2, log = { lines += it }))
        assertEquals(listOf("mark 2: no session recording is open to keep a copy of"), lines)
        assertEquals(0, queued.size)
    }
}
