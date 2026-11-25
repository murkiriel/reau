package com.reau.app.devtools

import com.reau.app.data.RideDiagnostics
import com.reau.app.devtools.FaultInjection.Fault
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The debug build's fault injection (sources in `src/debug`): what arming each fault changes for the places that
 * ask, that each is served once, and what the ride log says. The release build has none of it (FaultInjectionAbsenceInReleaseTest).
 */
class FaultInjectionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: File

    @Before
    fun setUp() {
        FaultInjection.reset()
        log = tmp.newFile("ride-fault.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        FaultInjection.reset()
        RideDiagnostics.testOnlyReset()
    }

    private fun told(): List<String> {
        RideDiagnostics.logAndWait("ui", "end")
        return log.readLines().filter { "[debug]" in it }
    }

    @Test
    fun `nothing is armed at the start, so every question is answered no`() {
        assertFalse(FaultInjection.swallowDecoderOutput())
        assertFalse(FaultInjection.takeFocusCycle())
        assertEquals(emptySet<Fault>(), FaultInjection.armedNow())
    }

    @Test
    fun `the decoder stall hides the decoder's output until the watch restarts it, once`() {
        FaultInjection.arm(Fault.DECODER_STALL)
        assertTrue(FaultInjection.swallowDecoderOutput())
        assertTrue("still, until it is served", FaultInjection.swallowDecoderOutput())
        FaultInjection.decoderRestarted()
        assertFalse("served: the pictures count again", FaultInjection.swallowDecoderOutput())
        FaultInjection.decoderRestarted() // a restart that was not the injected one says nothing
        val lines = told()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], "fault injection armed: DECODER_STALL" in lines[0])
        assertTrue(lines[1], "served" in lines[1])
    }

    @Test
    fun `the focus cycle is taken once for each press`() {
        FaultInjection.arm(Fault.FOCUS_CYCLE)
        FaultInjection.arm(Fault.FOCUS_CYCLE) // two presses before a tick are one request: the gate would not allow two in a second anyway
        assertTrue(FaultInjection.takeFocusCycle())
        assertFalse(FaultInjection.takeFocusCycle())
    }

    @Test
    fun `each fault is armed apart from the others`() {
        FaultInjection.arm(Fault.FOCUS_CYCLE)
        assertFalse(FaultInjection.swallowDecoderOutput())
        assertEquals(setOf(Fault.FOCUS_CYCLE), FaultInjection.armedNow())
        assertTrue(FaultInjection.takeFocusCycle())
        assertEquals(emptySet<Fault>(), FaultInjection.armedNow())
        FaultInjection.arm(Fault.DECODER_STALL)
        assertFalse(FaultInjection.takeFocusCycle())
        assertEquals(setOf(Fault.DECODER_STALL), FaultInjection.armedNow())
        assertEquals("the encoder mute went with its watch", setOf("DECODER_STALL", "FOCUS_CYCLE"), Fault.entries.map { it.name }.toSet())
    }

    @Test
    fun `the line of a fired notice says which and that it is the debug build`() {
        assertEquals("debug trigger fired: dash stuck (debug build only)", DebugTriggers.firedLine("dash stuck"))
    }
}
