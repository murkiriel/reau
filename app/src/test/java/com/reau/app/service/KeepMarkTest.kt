package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * What the service does for the rider's mark ([ProjectionService.keepMark]): the line of the mark first, then the ask to the sides to keep a copy
 * of the session recording (with the number of this mark), and a copy that fails costs the copy only. Before this test the order was proved by
 * reading the service's source (MarkFreezeIsWiredTest).
 */
class KeepMarkTest {
    private val events = mutableListOf<String>()
    private val problems = mutableListOf<String>()
    private val marks = RiderMarks(TimeZone.getTimeZone("UTC"))

    private open inner class Sides : ProjectionSides {
        override fun startAndroidAuto() = Unit
        override fun stopAndroidAuto() = Unit
        override fun startDash() = Unit
        override fun stopDash() = Unit
        override fun startVideo() = Unit
        override fun stopVideo() = Unit
    }

    private fun keep(sides: ProjectionSides?) =
        ProjectionService.keepMark(sides, marks, nowMs = 0, log = { problems += it }, note = { events += "line: $it" })

    @Test
    fun `the line of the mark comes first, then the ask to keep the recording, with the number of the mark`() {
        keep(object : Sides() {
            override fun freezeRecording(mark: Int): Boolean { events += "freeze $mark"; return true }
        })
        assertEquals(listOf("line: MARCA DO PILOTO 1", "freeze 1"), events)
        keep(object : Sides() {
            override fun freezeRecording(mark: Int): Boolean { events += "freeze $mark"; return true }
        })
        assertEquals(listOf("line: MARCA DO PILOTO 1", "freeze 1", "line: MARCA DO PILOTO 2", "freeze 2"), events)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `with no sides the mark is still written and nothing else happens`() {
        keep(null)
        assertEquals(listOf("line: MARCA DO PILOTO 1"), events)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a copy that fails is said in the log and the mark stands`() {
        keep(object : Sides() {
            override fun freezeRecording(mark: Int): Boolean = error("disk full")
        })
        assertEquals(listOf("line: MARCA DO PILOTO 1"), events)
        assertEquals(1, problems.size)
        assertTrue(problems.single(), "mark 1: keeping the session recording failed" in problems.single() && "disk full" in problems.single())
    }
}
