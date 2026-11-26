package com.reau.app.devtools

import android.content.Context
import android.content.ContextWrapper
import com.reau.app.data.RideDiagnostics
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What [DebugTriggers.fire] does when a button of the debug rows is pressed (C15 of the pending list: it had only tests of text): each name posts its own
 * notice and no other, with the temperature and the minutes the rows promise, and writes the line that says it was the debug build; a name that is none
 * of them posts nothing and writes nothing. The notices are a recorder here; that the real ones are the app's own is anchored in
 * ProjectionLogLinesAreWiredTest.
 */
class DebugTriggersFireTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var log: File
    private val context: Context = ContextWrapper(null)

    /** The calls the notices received, in order, as text. */
    private class Recorder : DebugTriggers.Notices {
        val calls = mutableListOf<String>()
        override fun dashStuck(context: Context) { calls += "dashStuck" }
        override fun phoneHot(context: Context, tempC: Int) { calls += "phoneHot $tempC" }
        override fun stoppedNoDash(context: Context, minutes: Int) { calls += "stoppedNoDash $minutes" }
    }

    @Before
    fun setUp() {
        ArrowTrial.stopStreet()
        log = tmp.newFile("ride-fire.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        ArrowTrial.stopStreet()
        RideDiagnostics.testOnlyReset()
    }

    private fun firedLines(): List<String> {
        RideDiagnostics.logAndWait("ui", "end")
        return log.readLines().filter { "[debug]" in it }
    }

    @Test
    fun `each notice name posts its own notice and no other, with the values of the rows`() {
        val expected = mapOf(
            "dash stuck" to "dashStuck",
            "phone hot" to "phoneHot ${DebugTriggers.HOT_TEMP_C}",
            "no dash stop" to "stoppedNoDash 1",
        )
        for ((name, call) in expected) {
            val recorder = Recorder()
            assertTrue(name, DebugTriggers.fire(context, name, recorder))
            assertEquals(name, listOf(call), recorder.calls)
        }
        assertNull("no street test came with them", ArrowTrial.street())
    }

    @Test
    fun `a fired notice writes one line that says which and that it is the debug build`() {
        DebugTriggers.fire(context, "phone hot", Recorder())
        DebugTriggers.fire(context, "dash stuck", Recorder())
        val lines = firedLines()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], DebugTriggers.firedLine("phone hot") in lines[0])
        assertTrue(lines[1], DebugTriggers.firedLine("dash stuck") in lines[1])
    }

    @Test
    fun `the street test posts no notice and starts the street name`() {
        val recorder = Recorder()
        assertTrue(DebugTriggers.fire(context, DebugTriggers.STREET_TEST, recorder))
        assertEquals(emptyList<String>(), recorder.calls)
        assertEquals(ArrowTrial.DEVANAGARI_STREET, ArrowTrial.street())
        assertTrue(DebugTriggers.firedLine(DebugTriggers.STREET_TEST) in firedLines().single())
    }

    @Test
    fun `a name that is none of them returns false, posts nothing, writes nothing and starts no test`() {
        for (name in listOf("", "unknown", "Dash Stuck", "dash stuck ", "phone  hot", "debug_fire_dash_stuck")) {
            val recorder = Recorder()
            assertFalse("'$name'", DebugTriggers.fire(context, name, recorder))
            assertEquals("'$name'", emptyList<String>(), recorder.calls)
        }
        assertEquals(emptyList<String>(), firedLines())
        assertNull(ArrowTrial.street())
    }
}
