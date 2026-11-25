package com.reau.app.aa

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server is off after an update of Android Auto: the decision is a pure rule over the link, the update time kept and the one now. */
class AaUpdatedServerOffTest {
    private fun decide(link: AaLinkState = AaLinkState.SERVER_OFF, seenOnAt: Long? = 1_000L, updatedAt: Long? = 2_000L) =
        AaUpdatedServerOff.decide(link, seenOnAt, updatedAt)

    @Test
    fun `server off, a value kept and Android Auto updated after it is the case`() {
        assertEquals(true, decide())
    }

    @Test
    fun `with no value kept (the first time) it is not said to be an update`() {
        assertEquals(false, decide(seenOnAt = null))
    }

    @Test
    fun `Android Auto not updated since the server worked is not the case`() {
        assertEquals(false, decide(seenOnAt = 2_000L, updatedAt = 2_000L))
        assertEquals(false, decide(seenOnAt = 3_000L, updatedAt = 2_000L))
    }

    @Test
    fun `one millisecond of update after the kept time is enough`() {
        assertEquals(true, decide(seenOnAt = 1_999L, updatedAt = 2_000L))
    }

    @Test
    fun `with the server on, or any state of the link but off, it is not the case`() {
        AaLinkState.entries.filter { it != AaLinkState.SERVER_OFF }.forEach { assertEquals("$it", false, decide(link = it)) }
    }

    @Test
    fun `without Android Auto installed it is not the case`() {
        assertEquals(false, decide(updatedAt = null))
        assertEquals(false, decide(seenOnAt = null, updatedAt = null))
    }

    @Test
    fun `nothing is kept until the server is seen on`() {
        assertNull(AaUpdatedServerOff.Seen(FakePrefs()).seenOnAt())
    }

    @Test
    fun `the update time kept is the one of the last time the server was seen on, and survives a new object`() {
        val prefs = FakePrefs()
        AaUpdatedServerOff.Seen(prefs).seenOn(1_000L)
        AaUpdatedServerOff.Seen(prefs).seenOn(2_000L)
        assertEquals(2_000L, AaUpdatedServerOff.Seen(prefs).seenOnAt())
    }

    @Test
    fun `the update is told only until the server works again`() {
        val seen = AaUpdatedServerOff.Seen(FakePrefs())
        seen.seenOn(1_000L)
        assertEquals(true, AaUpdatedServerOff.decide(AaLinkState.SERVER_OFF, seen.seenOnAt(), 2_000L))
        seen.seenOn(2_000L)
        assertEquals(false, AaUpdatedServerOff.decide(AaLinkState.SERVER_OFF, seen.seenOnAt(), 2_000L))
    }

    @Test
    fun `the log line has no name, address or country of the phone`() {
        val line = AaUpdatedServerOff.LOG_LINE
        assertEquals(false, Regex("""\d{2,}|ssid|mac""", RegexOption.IGNORE_CASE).containsMatchIn(line))
    }
}
