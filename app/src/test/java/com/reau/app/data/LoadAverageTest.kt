package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's load in the `device …` line: the load average from `/proc/loadavg` (tried once; a phone that does not let the app read it is told once and
 * never asked again) and the low-memory threshold. The reader that asks needs Android, so its source is read for what it must not do.
 */
class LoadAverageTest {
    private val base = DeviceState(
        batteryPct = 57, charging = false, batteryTempC = 31.5f, batteryMv = 4123,
        thermal = 1, headroom = null,
        appUsedMb = 45, appMaxMb = 512, phoneAvailMb = 1830, lowMemory = false,
        screenOn = true, interactive = true, dozing = false, powerSave = false, batteryExempt = false,
        bikeWifi = null, data = DeviceState.Data.CELL, dataValidated = true,
    )

    @Test
    fun `the first three numbers of the file are the load`() {
        assertEquals("0.52/0.58/0.59", LoadAverage.parse("0.52 0.58 0.59 1/467 8132\n"))
        assertEquals("12.00/3.10/0.07", LoadAverage.parse("12 3.1 0.07"))
    }

    @Test
    fun `text that is not three numbers is not a load`() {
        assertNull(LoadAverage.parse(""))
        assertNull(LoadAverage.parse("0.5 0.6"))
        assertNull(LoadAverage.parse("a b c"))
        assertNull(LoadAverage.parse("-1 0 0"))
        assertNull(LoadAverage.parse("NaN 0 0"))
        assertNull(LoadAverage.parse("Infinity 0 0"))
    }

    @Test
    fun `a file that can be read is read at every look`() {
        var told = 0
        val load = LoadAverage({ "0.10 0.20 0.30 1/1 1" }, { told++ })
        assertEquals("0.10/0.20/0.30", load.read())
        assertEquals("0.10/0.20/0.30", load.read())
        assertEquals(0, told)
    }

    @Test
    fun `a file that cannot be read is tried once, told once and never tried again`() {
        var tries = 0
        var told = 0
        val load = LoadAverage({ tries++; throw java.io.FileNotFoundException("/proc/loadavg (Permission denied)") }, { told++ })
        assertNull(load.read())
        assertNull(load.read())
        assertNull(load.read())
        assertEquals(1, tries)
        assertEquals(1, told)
    }

    @Test
    fun `a file that says something else is also given up on, and a note that throws changes nothing`() {
        var tries = 0
        val load = LoadAverage({ tries++; "garbage" }, { error("log closed") })
        assertNull(load.read())
        assertNull(load.read())
        assertEquals(1, tries)
    }

    @Test
    fun `the note says the file and that the line has no load, and is not taken for a look at the phone`() {
        assertTrue(LoadAverage.UNREADABLE_LINE, "/proc/loadavg" in LoadAverage.UNREADABLE_LINE && "not readable" in LoadAverage.UNREADABLE_LINE)
        assertFalse(LoadAverage.UNREADABLE_LINE.startsWith("device "))
    }

    @Test
    fun `the line has the low memory threshold and the load after the low flag, and leaves them out when unknown`() {
        val said = base.copy(lowMemoryAtMb = 512, loadAverage = "1.20/0.80/0.50").line()
        assertTrue(said, " phone=1830MB low=no lowat=512MB load=1.20/0.80/0.50 screen=on " in said)
        val unknown = base.line()
        assertTrue(unknown, " phone=1830MB low=no screen=on " in unknown)
        assertFalse(unknown, "lowat" in unknown || "load=" in unknown)
    }

    @Test
    fun `the reader reads the threshold and the load average and nothing of other processes`() {
        val reader = File("src/main/java/com/reau/app/service/DeviceStateReader.kt").readText()
        val code = reader.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")
        assertTrue(code, "lowMemoryAtMb = memory?.let { (it.threshold / MB).toInt() }," in code)
        assertTrue(code, "loadAverage = load.read()," in code)
        assertTrue(code, "LoadAverage { RideDiagnostics.log(\"device\", LoadAverage.UNREADABLE_LINE) }" in code)
        val loads = File("src/main/java/com/reau/app/data/LoadAverage.kt").readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (never in listOf("/proc/stat", "/proc/self", "/proc/\$", "listFiles", "ProcessBuilder", "Runtime.getRuntime().exec")) {
            assertFalse("$never reads more than the load", never in code + loads)
        }
    }
}
