package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The system's signs that memory is short (plan 35, a report of 25/11/2026: Android closed REau for low memory and the log said nothing before it): each sign
 * is written with its level's name and makes the projection look at the phone at once; a sign of real pressure makes the `device …` line come at every look
 * (5 s) for a minute; the kernel's `MemAvailable` and memory pressure join the line when the app may read them.
 */
class MemoryPressureTest {
    private val said = ArrayList<String>()
    private val looks = ArrayList<String>()
    private val signals = PressureSignals { said += it }.also { it.attach { why -> looks += why } }

    private val base = DeviceState(
        batteryPct = 57, charging = false, batteryTempC = 31.5f, batteryMv = 4123,
        thermal = 1, headroom = null,
        appUsedMb = 45, appMaxMb = 512, phoneAvailMb = 1830, lowMemory = false,
        screenOn = true, interactive = true, dozing = false, powerSave = false, batteryExempt = false,
        bikeWifi = null, data = DeviceState.Data.CELL, dataValidated = true,
    )

    @Test
    fun `every level has its name and an unknown one keeps its number`() {
        assertEquals("RUNNING_MODERATE", PressureSignals.levelName(5))
        assertEquals("RUNNING_LOW", PressureSignals.levelName(10))
        assertEquals("RUNNING_CRITICAL", PressureSignals.levelName(15))
        assertEquals("UI_HIDDEN", PressureSignals.levelName(20))
        assertEquals("BACKGROUND", PressureSignals.levelName(40))
        assertEquals("MODERATE", PressureSignals.levelName(60))
        assertEquals("COMPLETE", PressureSignals.levelName(80))
        assertEquals("level7", PressureSignals.levelName(7))
    }

    @Test
    fun `only running low, running critical and complete are pressure`() {
        val pressure = listOf(5, 10, 15, 20, 40, 60, 80, 0, 7).filter { PressureSignals.isPressure(it) }
        assertEquals(listOf(10, 15, 80), pressure)
    }

    @Test
    fun `a trim is written with the level's name and its number, and asks for a look at once`() {
        signals.trimmed(20, nowMs = 1_000)
        assertEquals(listOf("onTrimMemory(UI_HIDDEN, 20)"), said)
        assertEquals(listOf("trim UI_HIDDEN"), looks)
    }

    @Test
    fun `a trim of pressure says the lines come faster, and an onLowMemory too`() {
        signals.trimmed(10, nowMs = 1_000)
        signals.lowMemory(nowMs = 2_000)
        assertTrue(said[0], said[0].startsWith("onTrimMemory(RUNNING_LOW, 10): ") && "every 5 s for 60 s" in said[0])
        assertTrue(said[1], said[1].startsWith("onLowMemory(): ") && "every 5 s for 60 s" in said[1])
        assertEquals(listOf("trim RUNNING_LOW", "low-memory"), looks)
    }

    @Test
    fun `a sign of pressure makes the looks write at every look for 60 s and then no more`() {
        assertNull(signals.periodAt(0))
        signals.trimmed(15, nowMs = 10_000)
        assertEquals(5_000L, signals.periodAt(10_000))
        assertEquals(5_000L, signals.periodAt(70_000))
        assertNull(signals.periodAt(70_001))
    }

    @Test
    fun `a trim that is not pressure leaves the period as it is`() {
        for (level in listOf(5, 20, 40, 60)) signals.trimmed(level, nowMs = 10_000)
        assertNull(signals.periodAt(10_000))
    }

    @Test
    fun `onLowMemory and complete also start the faster looks`() {
        signals.lowMemory(nowMs = 1_000)
        assertEquals(5_000L, signals.periodAt(1_001))
        val other = PressureSignals { }
        other.trimmed(80, nowMs = 1_000)
        assertEquals(5_000L, other.periodAt(30_000))
    }

    @Test
    fun `a look that fails or a log that fails is not the system's problem`() {
        val rough = PressureSignals { error("log closed") }
        rough.attach { error("worker gone") }
        rough.trimmed(15, nowMs = 1_000)
        rough.lowMemory(nowMs = 2_000)
        assertEquals(5_000L, rough.periodAt(3_000))
    }

    @Test
    fun `only the look that is attached is taken away`() {
        val old: (String) -> Unit = { looks += "old $it" }
        val new: (String) -> Unit = { looks += "new $it" }
        signals.attach(old)
        signals.attach(new)
        signals.detach(old)
        signals.lowMemory(nowMs = 1_000)
        assertEquals(listOf("new low-memory"), looks)
        signals.detach(new)
        signals.lowMemory(nowMs = 2_000)
        assertEquals(listOf("new low-memory"), looks)
    }

    // ── the device line ───────────────────────────────────────────────────────────────────────────────────────

    private class Lines(fasterAt: (Long) -> Long?) {
        val written = ArrayList<String>()
        val log = DeviceStateLog(periodMs = 30_000, fasterAt) { written += it }
    }

    @Test
    fun `with the faster period the line comes at every look, says so, and goes back to 30 s`() {
        signals.trimmed(10, nowMs = 100_000)
        val l = Lines(signals::periodAt)
        l.log.offer(100_000, base)
        l.log.offer(105_000, base)
        l.log.offer(110_000, base)
        assertEquals(3, l.written.size)
        assertTrue(l.written[1], l.written[1].endsWith(" [memory 5 s]"))
        l.log.offer(160_000, base)
        assertTrue(l.written[3], l.written[3].endsWith(" [memory 5 s]"))
        l.log.offer(165_000, base)
        assertEquals("after the minute the looks write nothing until the 30 s", 4, l.written.size)
        l.log.offer(190_000, base)
        assertTrue(l.written[4], l.written[4].endsWith(" [30 s]"))
    }

    @Test
    fun `without a sign the line is the one of 30 s as before`() {
        val l = Lines(signals::periodAt)
        l.log.offer(0, base)
        l.log.offer(5_000, base)
        l.log.offer(30_000, base)
        assertEquals(2, l.written.size)
        assertTrue(l.written[1], l.written[1].endsWith(" [30 s]"))
    }

    @Test
    fun `a forced line is written at once with its reason and the period starts again from it`() {
        val l = Lines { null }
        l.log.offer(0, base)
        l.log.force(10_000, base.copy(lowMemory = true), "trim RUNNING_LOW")
        assertEquals(2, l.written.size)
        assertTrue(l.written[1], l.written[1].startsWith("device battery=57%") && " low=yes " in l.written[1] && l.written[1].endsWith(" [trim RUNNING_LOW]"))
        l.log.offer(35_000, base)
        assertEquals("not 30 s since the forced line", 2, l.written.size)
        l.log.offer(40_000, base)
        assertEquals(3, l.written.size)
    }

    @Test
    fun `the line has the kernel's memory and its pressure after the load, and leaves them out when unknown`() {
        val full = base.copy(loadAverage = "1.20/0.80/0.50", memAvailableMb = 912, memPressure = "0.12/0.05/0.01").line()
        assertTrue(full, " load=1.20/0.80/0.50 memavail=912MB psi=0.12/0.05/0.01 screen=on " in full)
        val unknown = base.line()
        assertFalse(unknown, "memavail" in unknown || "psi=" in unknown)
        val noPsi = base.copy(memAvailableMb = 912).line()
        assertTrue(noPsi, " memavail=912MB screen=on " in noPsi && "psi=" !in noPsi)
    }

    // ── what the kernel says ──────────────────────────────────────────────────────────────────────────────

    private val meminfo = "MemTotal:        5765744 kB\nMemFree:          140212 kB\nMemAvailable:    1868800 kB\nBuffers:            2104 kB\n"
    private val psi = "some avg10=0.12 avg60=0.05 avg300=0.01 total=1234567\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=8910\n"

    @Test
    fun `MemAvailable is read in MB from the meminfo text`() {
        assertEquals(1825, MemoryProc.parseAvailableMb(meminfo))
        assertNull(MemoryProc.parseAvailableMb("MemTotal: 5765744 kB\n"))
        assertNull(MemoryProc.parseAvailableMb(""))
        assertNull(MemoryProc.parseAvailableMb("MemAvailable: lots kB"))
    }

    @Test
    fun `the some line of the pressure file gives the three averages`() {
        assertEquals("0.12/0.05/0.01", MemoryProc.parsePressure(psi))
        assertEquals("12.50/3.00/0.07", MemoryProc.parsePressure("some avg10=12.5 avg60=3 avg300=0.07 total=1\n"))
        assertNull("only the full line", MemoryProc.parsePressure("full avg10=0.00 avg60=0.00 avg300=0.00 total=8910\n"))
        assertNull(MemoryProc.parsePressure(""))
        assertNull(MemoryProc.parsePressure("some avg10=. avg60=. avg300=. total=1"))
    }

    @Test
    fun `a pressure file that can be read is read at every look`() {
        var told = 0
        val proc = MemoryProc({ meminfo }, { psi }, { told++ })
        assertEquals("0.12/0.05/0.01", proc.pressure())
        assertEquals("0.12/0.05/0.01", proc.pressure())
        assertEquals(1825, proc.availableMb())
        assertEquals(0, told)
    }

    @Test
    fun `a pressure file that cannot be read is tried once, told once and never tried again`() {
        var tries = 0
        var told = 0
        val proc = MemoryProc({ meminfo }, { tries++; throw java.io.FileNotFoundException("/proc/pressure/memory (Permission denied)") }, { told++ })
        assertNull(proc.pressure())
        assertNull(proc.pressure())
        assertEquals(1, tries)
        assertEquals(1, told)
        assertEquals("the meminfo is a file of its own", 1825, proc.availableMb())
    }

    @Test
    fun `a note that throws changes nothing, and a meminfo that fails leaves only memavail out`() {
        val proc = MemoryProc({ error("no meminfo") }, { "garbage" }, { error("log closed") })
        assertNull(proc.pressure())
        assertNull(proc.pressure())
        assertNull(proc.availableMb())
    }

    @Test
    fun `the note says the file and that the line has no psi, and is not taken for a look at the phone`() {
        assertTrue(MemoryProc.UNREADABLE_LINE, "/proc/pressure/memory" in MemoryProc.UNREADABLE_LINE && "no psi=" in MemoryProc.UNREADABLE_LINE)
        assertFalse(MemoryProc.UNREADABLE_LINE.startsWith("device "))
    }

    // ── where it is wired (the source: the Android side needs a phone) ────────────────────────────────

    private fun code(path: String) = File("src/main/java/com/reau/app/$path").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)(^|\\s)//.*$"), "")

    @Test
    fun `the reader reads the kernel's memory and the pressure with the note on the device tag`() {
        val reader = code("service/DeviceStateReader.kt")
        assertTrue(reader, "memAvailableMb = procMemory.availableMb()," in reader)
        assertTrue(reader, "memPressure = procMemory.pressure()," in reader)
        assertTrue(reader, "RideDiagnostics.log(\"device\", MemoryProc.UNREADABLE_LINE)" in reader)
    }

    @Test
    fun `the application hears both signs and the projection's look takes them`() {
        val app = code("REauApp.kt")
        assertTrue(app, "override fun onTrimMemory(level: Int)" in app && "MemoryPressure.signals.trimmed(level," in app)
        assertTrue(app, "override fun onLowMemory()" in app && "MemoryPressure.signals.lowMemory(" in app)
        val service = code("service/ProjectionService.kt")
        assertTrue(service, "DeviceStateLog(DEVICE_PERIOD_MS, signals::periodAt)" in service)
        assertTrue(service, "device.force(" in service && "signals.attach(onSign)" in service && "signals.detach(it)" in service)
        // The look runs on the worker, where the reader lives: the main thread only hands it over.
        assertTrue(service, "worker.execute { look(why) }" in service)
    }

    @Test
    fun `the signs and the proc reads start no thread`() {
        for (file in listOf("data/MemoryPressure.kt", "data/MemoryProc.kt")) {
            val source = code(file)
            for (never in listOf("Thread(", "thread(", "Executors", "Timer", "Handler(", "launch", "ProcessBuilder", "/proc/stat", "/proc/self")) {
                assertFalse("$file has $never", never in source)
            }
        }
    }
}
