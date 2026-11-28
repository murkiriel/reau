package com.reau.app.devtools

import android.content.ContextWrapper
import com.reau.app.dash.DashDump
import com.reau.app.dash.DashSession
import com.reau.app.dash.protocol.DashCommands
import com.reau.app.data.RideDiagnostics
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The debug build's street-name test (25/11/2026): a name in Devanagari goes to the dash by the field of the real street's name (the title of the gold
 * band and the nav packet's street), as UTF-8, so the owner sees on the bench or on the bike whether the dash draws it. Release has none of it
 * (StreetTrialAbsenceInReleaseTest).
 */
class StreetTrialTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: File

    /** The name of the test, letter by letter: चाँदनी चौक. */
    private val name = "चाँदनी चौक"

    @Before
    fun setUp() {
        ArrowTrial.stopStreet()
        log = tmp.newFile("ride-street.log")
        RideDiagnostics.testOnlySetFile(log)
    }

    @After
    fun tearDown() {
        ArrowTrial.stopStreet()
        scope.cancel()
        RideDiagnostics.testOnlyReset()
    }

    private fun streetOf(packet: ByteArray): ByteArray =
        DashDump.outgoingTlvs(packet).single { it.type == 0x05 && it.sub == 0x01 }.value

    @Test
    fun `the name is Chandni Chowk in Devanagari`() {
        assertEquals(name, ArrowTrial.DEVANAGARI_STREET)
        assertEquals("चाँदनी चौक", ArrowTrial.DEVANAGARI_STREET)
    }

    @Test
    fun `off, with no street and no bytes, the real streets keep going as Latin-1`() {
        assertNull(ArrowTrial.street())
        assertNull(ArrowTrial.streetBytes(name, DashCommands.ROAD_MAX))
        assertArrayEquals("Rua Jose".toByteArray(Charsets.ISO_8859_1), DashCommands.latin1("Rua Jose", DashCommands.ROAD_MAX))
        assertArrayEquals("??".toByteArray(Charsets.ISO_8859_1), DashCommands.latin1("\u091A\u093E", DashCommands.ROAD_MAX))
    }

    @Test
    fun `on, the name goes as UTF-8 cut at the field's size on a letter's edge, and only that name`() {
        ArrowTrial.sendStreet()
        assertEquals(name, ArrowTrial.street())
        val bytes = ArrowTrial.streetBytes(name, DashCommands.ROAD_MAX)!!
        assertTrue("fits the 19 bytes of the field: ${bytes.size}", bytes.size <= DashCommands.ROAD_MAX)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("no letter is half sent: $text", name.startsWith(text) && !text.contains('�'))
        assertEquals("the first word and the space (18 + 1 bytes)", "चाँदनी ", text)
        assertNull("another street is not touched", ArrowTrial.streetBytes("Rua Jose", DashCommands.ROAD_MAX))
        assertEquals("the whole name when the field is larger", name, String(ArrowTrial.streetBytes(name, 64)!!, Charsets.UTF_8))
    }

    @Test
    fun `the nav packet and the route card carry the name, with no guidance too, and the real street comes back with Stop`() {
        val session = DashSession(scope)
        assertNull(session.navPacketNow())
        val before = session.routeCardNow()
        ArrowTrial.sendStreet()
        val packet = session.navPacketNow()
        assertNotNull("the street alone makes the nav packet", packet)
        assertTrue(String(streetOf(packet!!), Charsets.UTF_8).trimEnd('\u0000'), String(streetOf(packet), Charsets.UTF_8).startsWith("चाँदनी"))
        val card = session.routeCardNow()
        assertFalse("the card changes", before.contentEquals(card))
        assertTrue("the card has the name's bytes", indexOf(card, "चाँदनी".toByteArray(Charsets.UTF_8)) >= 0)
        ArrowTrial.stopStreet()
        assertNull(session.navPacketNow())
        assertArrayEquals(before, session.routeCardNow())
    }

    @Test
    fun `with guidance running the name replaces only the street, the arrow and the distances stay`() {
        val session = DashSession(scope)
        session.updateNavInfo(com.reau.app.dash.nav.DashManeuverGlyphs.TURN_LEFT, 500, DashCommands.NAV_UNIT_METERS, 12, DashCommands.NAV_UNIT_KM_TENTHS, "08:15", "Rua X")
        val real = session.navPacketNow()!!
        ArrowTrial.sendStreet()
        val during = session.navPacketNow()!!
        assertEquals(real.size - "Rua X".length + 18 + 1, during.size)
        ArrowTrial.stopStreet()
        assertArrayEquals(real, session.navPacketNow())
    }

    @Test
    fun `the trigger fires through the same line as the others and the end is in the log`() {
        assertEquals("street in Devanagari", DebugTriggers.STREET_TEST)
        assertTrue(DebugTriggers.fire(ContextWrapper(null), DebugTriggers.STREET_TEST))
        assertEquals(ArrowTrial.DEVANAGARI_STREET, ArrowTrial.street())
        ArrowTrial.stopStreet()
        RideDiagnostics.logAndWait("ui", "end")
        val lines = log.readLines().filter { "[debug]" in it }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], "debug trigger fired: street in Devanagari (debug build only)" in lines[0])
        assertTrue(lines[1], "street name test ended" in lines[1])
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
