package com.reau.app.dash

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ride log's note when the dash's firmware is not the one the last session saw (P31 Joy): the rule alone ([FirmwareChange]), and the
 * dash's readings writing it when `0B02` comes, with what an earlier run kept as "the last version seen".
 */
class FirmwareChangeTest {
    /** The `0B02` text as the dash sends it: a status byte, a byte, then each partition as ASCII `BB.MM.mm`, then zeros. */
    private fun answer(status: String = "AA", vararg parts: String): String =
        status + "00" + parts.joinToString("") { part -> part.map { "%02X".format(it.code) }.joinToString("") } + "0000"

    private val v1 = answer("AA", "00.02.07", "01.08.05", "02.01.01")
    private val v2 = answer("AA", "00.02.07", "01.09.00", "02.01.01")

    // ── the rule ──

    @Test
    fun `the first answer only records the version`() {
        assertEquals("dash firmware: 00.02.07 01.08.05 02.01.01", FirmwareChange.line(null, v1))
    }

    @Test
    fun `the same versions say nothing, whatever else the answer holds`() {
        assertNull(FirmwareChange.line(v1, v1))
        assertNull("a status byte is not a version", FirmwareChange.line(v1, answer("55", "00.02.07", "01.08.05", "02.01.01")))
    }

    @Test
    fun `a different version says from what to what`() {
        assertEquals("dash firmware changed from 00.02.07 01.08.05 02.01.01 to 00.02.07 01.09.00 02.01.01", FirmwareChange.line(v1, v2))
        assertEquals(
            "a partition more is a change too",
            "dash firmware changed from 00.02.07 to 00.02.07 01.08.05",
            FirmwareChange.line(answer("AA", "00.02.07"), answer("AA", "00.02.07", "01.08.05")),
        )
    }

    @Test
    fun `an answer with no readable version says nothing, and a kept one that was unreadable counts as nothing kept`() {
        assertNull(FirmwareChange.line(v1, null))
        assertNull(FirmwareChange.line(v1, "AA00"))
        assertNull(FirmwareChange.line(null, "AA00"))
        assertEquals("dash firmware: 00.02.07", FirmwareChange.line("AA00", answer("AA", "00.02.07")))
    }

    @Test
    fun `only the versions are written, as the numbers with two digits each`() {
        assertEquals("00.02.07 01.08.05 02.01.01", FirmwareChange.versions(v1))
        assertNull(FirmwareChange.versions(null))
        assertNull(FirmwareChange.versions("AA00"))
        for (text in listOf(FirmwareChange.line(null, v1), FirmwareChange.line(v1, v2))) {
            assertTrue("nothing but words and versions in $text", Regex("""[a-z0-9. ]+""").matches(text.orEmpty().replace(":", "")))
        }
    }

    // ── the readings write it ──

    private val queue = ArrayDeque<Runnable>()
    private val prefs = FakePrefs()
    private val savedSaver = DashReadings.saver
    private val savedSay = DashReadings.say
    private val lines = mutableListOf<String>()

    private fun runQueue() {
        while (true) (queue.removeFirstOrNull() ?: return).run()
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun heard(hex: String, nowMs: Long) = DashReadings.onValue(0x0B, 0x02, bytes(hex), nowMs)

    @Before
    fun setUp() {
        DashReadings.saver = Executor { queue.addLast(it) }
        DashReadings.attach(DashReadingsStore(prefs))
        DashReadings.clear()
        runQueue()
        prefs.edit().clear().commit()
        DashReadings.say = { lines += it }
    }

    @After
    fun tearDown() {
        DashReadings.say = savedSay
        DashReadings.clear()
        DashReadings.testOnlyDetach()
        DashReadings.saver = savedSaver
    }

    @Test
    fun `with nothing kept the first answer is written once, and the same answer again says nothing`() {
        heard(v1, 10_000)
        assertEquals(listOf("dash firmware: 00.02.07 01.08.05 02.01.01"), lines)
        heard(v1, 11_000)
        heard(v1, 12_000)
        assertEquals("said once", 1, lines.size)
    }

    @Test
    fun `the version an earlier run kept is the one the new answer is compared with`() {
        // The last run kept v1 (the store loads it at the start of this one); this session hears v2.
        DashReadingsStore(prefs).save(mapOf("0B02" to DashReading(v1, 1_000L, 5)))
        DashReadings.attach(DashReadingsStore(prefs))
        heard(v2, 10_000)
        assertEquals(listOf("dash firmware changed from 00.02.07 01.08.05 02.01.01 to 00.02.07 01.09.00 02.01.01"), lines)
        heard(v2, 11_000)
        assertEquals("said once", 1, lines.size)
    }

    @Test
    fun `an earlier run that kept the same version leaves the log quiet`() {
        DashReadingsStore(prefs).save(mapOf("0B02" to DashReading(v1, 1_000L, 5)))
        DashReadings.attach(DashReadingsStore(prefs))
        heard(v1, 10_000)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `the version is kept for the next run, and a change in this run is written against the one just heard`() {
        heard(v1, 10_000)
        DashReadings.flush()
        runQueue()
        assertEquals(v1, DashReadingsStore(prefs).load().getValue("0B02").hex)
        lines.clear()
        heard(v2, 20_000)
        assertEquals(listOf("dash firmware changed from 00.02.07 01.08.05 02.01.01 to 00.02.07 01.09.00 02.01.01"), lines)
    }

    @Test
    fun `forgetting the bike makes the next answer a first one again`() {
        heard(v1, 10_000)
        DashReadings.clear()
        runQueue()
        lines.clear()
        heard(v2, 30_000)
        assertEquals(listOf("dash firmware: 00.02.07 01.09.00 02.01.01"), lines)
    }

    @Test
    fun `the other codes the dash says write no firmware line`() {
        DashReadings.onValue(0x0C, 0x14, byteArrayOf(0x03), 10_000L)
        DashReadings.onValue(0x0F, 0x0B, "ABC".toByteArray(), 10_100L)
        DashReadings.onValue(0x03, 0x05, byteArrayOf(0xAA.toByte()), 10_200L)
        assertEquals(emptyList<String>(), lines)
    }
}
