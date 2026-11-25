package com.reau.app.dash

import com.reau.app.dash.ChannelCrowd.Row
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line of how crowded the dash's channel is (D4, 25/11/2026): counted from the scan list Android has cached, never from a scan the app asks for, and
 * without the name or the address of any network.
 */
class ChannelCrowdTest {
    private val nowUs = 1_000_000_000L

    private fun row(mhz: Int, dbm: Int, ageS: Long = 42, ssid: String = "") = Row(ssid, mhz, dbm, nowUs - ageS * 1_000_000L)

    @Test
    fun `networks on the same channel and on the channels that overlap it in 2_4 GHz are counted apart, each with its strongest`() {
        // The dash on 2452 MHz (channel 9): overlapping are 2432 to 2472 (4 channels either side), not 2477.
        val rows = listOf(
            row(2452, -71), row(2452, -80), row(2452, -90),
            row(2432, -70), row(2447, -64), row(2457, -75), row(2472, -88), row(2437, -92),
            row(2477, -50), row(2412, -40), row(5180, -45), row(5745, -60),
        )
        val c = ChannelCrowd.crowd(rows, 2452, null, nowUs)
        assertEquals(3, c.same)
        assertEquals(-71, c.sameStrongest)
        assertEquals(5, c.overlapping)
        assertEquals(-64, c.overlappingStrongest)
        assertEquals("channel: dash 2452 MHz; same channel 3 (strongest -71 dBm), overlapping 5 (strongest -64 dBm); cache 42 s old", ChannelCrowd.line(rows, 2452, null, nowUs))
    }

    @Test
    fun `the dash's own network is not counted, and the age is the newest result of the whole list`() {
        val rows = listOf(row(2452, -40, ageS = 40, ssid = "DASH_ONE"), row(2452, -75, ageS = 40), row(2457, -70, ageS = 12), row(5180, -50, ageS = 300))
        val c = ChannelCrowd.crowd(rows, 2452, "DASH_ONE", nowUs)
        assertEquals("only the other network on the channel", 1, c.same)
        assertEquals(-75, c.sameStrongest)
        assertEquals(12L, c.cacheAgeS)
        assertEquals("with the dash's name unknown it is one of the networks", 2, ChannelCrowd.crowd(rows, 2452, null, nowUs).same)
    }

    @Test
    fun `in 5 GHz only the same channel counts, and a neighbour of 2_4 GHz never does`() {
        val rows = listOf(row(5180, -60), row(5180, -72), row(5200, -50), row(5220, -55), row(2412, -40), row(2437, -45))
        val c = ChannelCrowd.crowd(rows, 5180, null, nowUs)
        assertEquals(2, c.same)
        assertEquals(-60, c.sameStrongest)
        assertEquals(0, c.overlapping)
        assertNull(c.overlappingStrongest)
        assertEquals("channel: dash 5180 MHz; same channel 2 (strongest -60 dBm), overlapping 0; cache 42 s old", ChannelCrowd.line(rows, 5180, null, nowUs))
        // and a 5 GHz network is not a neighbour of a dash on 2.4 GHz, whatever the frequencies
        assertEquals(0, ChannelCrowd.crowd(listOf(row(5180, -40)), 2412, null, nowUs).overlapping)
    }

    @Test
    fun `a channel nobody else is on says zero, with no strongest`() {
        assertEquals("channel: dash 2412 MHz; same channel 0, overlapping 0; cache 5 s old", ChannelCrowd.line(listOf(row(2462, -60, ageS = 5)), 2412, null, nowUs))
    }

    @Test
    fun `an empty or unreadable list is told once, then not again until a list shows`() {
        assertTrue(ChannelCrowd.isEmpty(null))
        assertTrue(ChannelCrowd.isEmpty(emptyList()))
        val teller = ChannelCrowd.Teller()
        assertEquals("channel: no scan results cached", teller.look(null, 2452, null, nowUs))
        assertNull(teller.look(emptyList(), 2452, null, nowUs))
        assertNull(teller.look(null, 2452, null, nowUs))
        assertTrue(teller.look(listOf(row(2452, -60)), 2452, null, nowUs)!!.startsWith("channel: dash 2452 MHz"))
        assertEquals("said again after a list came and went", ChannelCrowd.EMPTY_LINE, teller.look(null, 2452, null, nowUs))
    }

    @Test
    fun `the line carries no name and no address of any network, the dash's included, whatever the cache holds`() {
        val names = listOf("CasaDaMaria", "Vizinho 5G", "DASH_PRIVATE_NAME", "Cafe Wi-Fi")
        val rows = names.mapIndexed { i, n -> row(2452 + (i % 2) * 5, -60 - i, ssid = n) }
        val allowed = Regex("""^channel: dash \d+ MHz; same channel \d+( \(strongest -?\d+ dBm\))?, overlapping \d+( \(strongest -?\d+ dBm\))?; cache \d+ s old$""")
        for (dashSsid in listOf("DASH_PRIVATE_NAME", null)) {
            val line = ChannelCrowd.line(rows, 2452, dashSsid, nowUs)
            assertTrue(line, allowed.matches(line))
            for (n in names) assertFalse("$n in: $line", n in line)
            assertFalse("a hardware address in: $line", Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").containsMatchIn(line))
        }
        assertTrue(allowed.matches(ChannelCrowd.line(rows, 5180, "DASH_PRIVATE_NAME", nowUs)))
    }

    @Test
    fun `nothing in the code of the line asks for a scan or keeps an address`() {
        val dir = "src/main/java/com/reau/app/dash/"
        val crowd = File(dir + "ChannelCrowd.kt").readText()
        val probe = File(dir + "PhoneLinkProbe.kt").readText()
        for ((name, text) in listOf("ChannelCrowd" to crowd, "PhoneLinkProbe" to probe)) {
            assertFalse("$name asks for a scan", Regex("""\.startScan\(""").containsMatchIn(text))
            assertFalse("$name reads an address", Regex("""\bBSSID\b|\.bssid\b|macAddress""").containsMatchIn(text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")))
        }
        assertFalse("the Row has no address field", "val bssid" in crowd.lowercase())
    }

    @Test
    fun `the session writes the channel every 30 s while streaming, from the cached list, and the service gives it the list`() {
        assertEquals(30, ChannelCrowd.EVERY_TICKS)
        val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        val fn = session.substringAfter("private fun logChannel(tick: Int) {").substringBefore("\n    }\n")
        assertTrue("only while streaming", "_state.value != DashState.STREAMING" in fn)
        assertTrue("every EVERY_TICKS s after the first", "tick % ChannelCrowd.EVERY_TICKS != 0" in fn)
        assertTrue("the cached list", "channelScan()" in fn && "channelTeller.look(" in fn)
        assertTrue("called with the heartbeat", "logChannel(n)" in session)
        assertTrue("the teller is a new one for each session", session.contains("channelTeller = ChannelCrowd.Teller()\n                launchStatsLine()"))
        assertTrue(File("src/main/java/com/reau/app/service/LiveSides.kt").readText().contains("channelScan = com.reau.app.dash.PhoneLinkProbe(context)::readScan"))
    }
}
