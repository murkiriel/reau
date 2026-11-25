package com.reau.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The service and the receiver need Android to run, so the lines that hand the rider's mark to the copy of the session recording are read from
 * the source, as [DashRecoveryIsWiredTest] does. What they hand it to is proved by what it does: the order of the mark's line and the ask
 * ([KeepMarkTest]) and the copy off the caller's thread ([com.reau.app.aa.MarkKeepTest]), and what the copy holds (MarkFreezerTest).
 */
class MarkFreezeIsWiredTest {
    private val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
    private val live = File("src/main/java/com/reau/app/service/LiveSides.kt").readText()
    private val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()

    @Test
    fun `a mark is handed to the keeping of the recording, off the main thread`() {
        val mark = service.substringAfter("ACTION_MARK -> worker.execute {").substringBefore("ACTION_REFIT")
        assertTrue(mark.contains("keepMark(sides, marks, System.currentTimeMillis(), ::log)"))
    }

    @Test
    fun `the live sides hand it to the receiver, which keeps it on a thread of its own`() {
        assertTrue(live.contains("override fun freezeRecording(mark: Int): Boolean = receiver?.freezeRecording(mark) ?: false"))
        assertTrue(receiver.contains("fun freezeRecording(mark: Int): Boolean = MarkFreezer.keep(freezer, sentRecorder, freezeIo, mark)"))
        assertTrue(receiver.contains("Thread(it, \"mark-freeze\")"))
    }

    @Test
    fun `the session recorder in use is the ring one, so the copy has two halves to take`() {
        assertTrue(receiver.contains("ring = true"))
        val handler = File("src/main/java/com/reau/app/aa/SessionRecorder.kt").readText().substringAfter("internal class RecordingHandler")
        assertFalse("nothing is copied on the read thread", handler.contains("snapshot") || handler.contains("freeze"))
    }
}
