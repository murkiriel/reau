package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkReportTest {
    private val good = LinkSample(rssiDbm = -58, txMbps = 72, rxMbps = 65, mhz = 5180, powerSave = false, screenOn = true)

    @Test
    fun `the band comes from the channel frequency`() {
        assertEquals("2.4 GHz", LinkReport.band(2437))
        assertEquals("5 GHz", LinkReport.band(5180))
        assertEquals("5 GHz", LinkReport.band(5885))
        assertEquals("6 GHz", LinkReport.band(5955))
        assertEquals("?", LinkReport.band(null))
        assertEquals("?", LinkReport.band(0))
    }

    @Test
    fun `a moment says signal, speeds, band, power saving and screen`() {
        assertEquals("rssi=-58 dBm tx=72 rx=65 Mbps 5 GHz powersave=off screen=on", LinkReport.describe(good))
        assertEquals(
            "rssi=-58 dBm tx=72 rx=65 Mbps 5 GHz powersave=on screen=off",
            LinkReport.describe(good.copy(powerSave = true, screenOn = false)),
        )
    }

    @Test
    fun `what Android did not say is a question mark, and no reading says so`() {
        assertEquals("rssi=? tx=? rx=? Mbps ? powersave=? screen=?", LinkReport.describe(LinkSample()))
        assertEquals("no reading", LinkReport.describe(null))
    }

    @Test
    fun `a reading of the phone's own Wi-Fi is marked, so it is never mistaken for the dash's`() {
        val line = LinkReport.describe(good.copy(viaDashNetwork = false))
        assertTrue(line, line.endsWith("(NOT the dash network: the phone's active Wi-Fi)"))
    }

    @Test
    fun `the trend lists the signal and the speed oldest first, with the minute it covers`() {
        val samples = listOf(good, good.copy(rssiDbm = -70, txMbps = 24), good.copy(rssiDbm = -85, txMbps = 6), null)
        assertEquals("last 20 s, every 5 s: rssi -58 -70 -85 ? | tx 72 24 6 ?", LinkReport.trend(samples))
        assertEquals("no history", LinkReport.trend(emptyList()))
    }

    @Test
    fun `at a failure the line has the moment and the minute before it, and survives having neither`() {
        val line = LinkReport.atFailure(good.copy(rssiDbm = -88), listOf(good, good.copy(rssiDbm = -88)))
        assertEquals(
            "link at failure: rssi=-88 dBm tx=72 rx=65 Mbps 5 GHz powersave=off screen=on | last 10 s, every 5 s: rssi -58 -88 | tx 72 72",
            line,
        )
        assertEquals("link at failure: no reading | no history", LinkReport.atFailure(null, emptyList()))
    }

    /** 22:35:25 on 19/10/2026: the line said 5 GHz, tx=468, the home network, as if it were the dash's. */
    @Test
    fun `at a failure with the dash network gone, the last sample of the dash network stands in for the moment`() {
        val elsewhere = LinkSample(rssiDbm = -57, txMbps = 468, rxMbps = 6, mhz = 5_200, viaDashNetwork = false)
        val line = LinkReport.atFailure(elsewhere, listOf(good, good.copy(rssiDbm = -60)))
        assertEquals(
            "link at failure: last sample of the dash network: rssi=-60 dBm tx=72 rx=65 Mbps 5 GHz powersave=off screen=on | " +
                "last 10 s, every 5 s: rssi -58 -60 | tx 72 72",
            line,
        )
        assertFalse(line, "468" in line)
    }

    @Test
    fun `a probe that failed at the moment of the failure also falls back to the last sample of the dash network`() {
        val line = LinkReport.atFailure(null, listOf(good))
        assertTrue(line, line.startsWith("link at failure: last sample of the dash network: rssi=-58 dBm"))
    }

    @Test
    fun `with nothing from the dash network at all the other network is still named for what it is`() {
        val elsewhere = LinkSample(rssiDbm = -57, viaDashNetwork = false)
        val line = LinkReport.atFailure(elsewhere, listOf(elsewhere))
        assertTrue(line, "NOT the dash network" in line)
    }

    @Test
    fun `the history keeps only the last minute, oldest first`() {
        val history = LinkHistory()
        repeat(LinkReport.HISTORY + 5) { history.add(good.copy(rssiDbm = -it)) }
        val kept = history.snapshot()
        assertEquals(LinkReport.HISTORY, kept.size)
        assertEquals(-5, kept.first()?.rssiDbm)
        assertEquals(-(LinkReport.HISTORY + 4), kept.last()?.rssiDbm)
        history.clear()
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `the history takes a missing reading as a gap, not as a stop`() {
        val history = LinkHistory(capacity = 3)
        history.add(good)
        history.add(null)
        history.add(good.copy(rssiDbm = -61))
        assertEquals(listOf(-58, null, -61), history.snapshot().map { it?.rssiDbm })
    }
}
