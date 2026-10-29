package com.reau.app.dash

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audit of 20/10/2026 (bug 10): saving the bike's readings re-encrypted up to 14 values through the Keystore on the thread that
 * receives the dash's packets. Now the receive thread only hands the changed ones to a thread of the storage's own.
 */
class DashReadingsAsyncSaveTest {
    private val queue = ArrayDeque<Runnable>()
    private val prefs = FakePrefs()
    private val savedSaver = DashReadings.saver

    private fun runQueue() {
        while (true) (queue.removeFirstOrNull() ?: return).run()
    }

    @Before
    fun setUp() {
        DashReadings.saver = Executor { queue.addLast(it) }
        DashReadings.attach(DashReadingsStore(prefs))
        DashReadings.clear()
        runQueue()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        DashReadings.clear()
        DashReadings.testOnlyDetach()
        DashReadings.saver = savedSaver
    }

    @Test
    fun `the receive thread writes nothing to the storage, it hands the change over`() {
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 10_000L)
        assertTrue("written on the caller's thread: ${prefs.all}", prefs.all.isEmpty())
        assertEquals("one task for the storage's thread", 1, queue.size)
        runQueue()
        assertEquals(setOf("0C14"), prefs.all.keys)
    }

    @Test
    fun `a value that did not change is not saved again`() {
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 10_000L)
        runQueue()
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 20_000L) // the same value, long after
        DashReadings.flush()
        assertTrue("saved again: ${queue.size}", queue.isEmpty())
    }

    @Test
    fun `only the codes that changed are written`() {
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 10_000L)
        DashReadings.onValue(0x0C, 0x07, byteArrayOf(0x00, 0xCA.toByte()), 10_100L)
        DashReadings.flush()
        runQueue()
        assertEquals(setOf("0C14", "0C07"), prefs.all.keys)

        prefs.edit().clear().commit()
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x04), 30_000L) // only this one changed
        DashReadings.flush() // the session ends: what changed goes to the storage
        runQueue()
        assertEquals("the other code was written again", setOf("0C14"), prefs.all.keys)
    }

    @Test
    fun `forgetting the bike after a save that has not run yet leaves nothing stored`() {
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 10_000L)
        DashReadings.clear() // queued behind the save
        runQueue()
        assertTrue("the value came back: ${prefs.all}", prefs.all.isEmpty())
        assertTrue(DashReadings.all.value.isEmpty())
    }
}
