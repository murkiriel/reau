package com.reau.app.dash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logs of 19 and 20/10/2026: four of ten entries ended in `Auth timed out` with the dash having sent no key; the counter of its first
 * heartbeat was 17, 19, 12 and 22 in those, and 28 to 39 in eight of the nine good ones (the ninth, 13, was the exception).
 */
class DashReadinessTest {
    private val failed = listOf(17, 19, 12, 22)
    private val good = listOf(32, 30, 34, 33, 36, 28, 39)

    private fun bytes(counter: Int) = byteArrayOf(0x4B, 0x31, 0x47, (counter shr 8).toByte(), counter.toByte())

    @Test
    fun `the counter is read from the heartbeat the dash sends, and only from that`() {
        assertEquals(34, DashReadiness.counterOf(byteArrayOf(0x4B, 0x31, 0x47, 0x00, 0x22)))
        assertEquals(12, DashReadiness.counterOf(bytes(12)))
        assertEquals("two bytes", 300, DashReadiness.counterOf(bytes(300)))
        assertNull("not K1G", DashReadiness.counterOf(byteArrayOf(0x00, 0x31, 0x47, 0x00, 0x22)))
        assertNull("too short", DashReadiness.counterOf(byteArrayOf(0x4B, 0x31, 0x47, 0x22)))
        assertNull(DashReadiness.counterOf(ByteArray(0)))
    }

    @Test
    fun `the counter goes up one a second from the first one seen`() {
        assertEquals(12, DashReadiness.counterAt(12, 1_000, 1_000))
        assertEquals(12, DashReadiness.counterAt(12, 1_000, 1_999))
        assertEquals(13, DashReadiness.counterAt(12, 1_000, 2_000))
        assertEquals(26, DashReadiness.counterAt(12, 1_000, 15_000))
        assertEquals("a clock that went back", 12, DashReadiness.counterAt(12, 1_000, 500))
    }

    @Test
    fun `the four failed entries are not ready at the entry and become ready, the good ones are ready at once`() {
        for (counter in failed) assertFalse("$counter", DashReadiness.ready(counter, 0, 0))
        assertTrue(DashReadiness.ready(12, 0, 14_000))
        assertFalse(DashReadiness.ready(12, 0, 13_999))
        for (counter in good) assertTrue("$counter", DashReadiness.ready(counter, 0, 0))
        assertTrue("a dash whose counter was not seen keeps the old rules", DashReadiness.ready(null, 0, 0))
    }

    @Test
    fun `the time to authenticate runs from the dash being ready, 15 s from the entry for a dash that already is`() {
        // entered at 100 000 ms; the first heartbeat at the same moment
        val at = 100_000L
        for (counter in good) assertEquals("$counter", at + 15_000, DashReadiness.deadlineMs(at, counter, at))
        assertEquals("17: ready 9 s in, so 24 s", at + 24_000, DashReadiness.deadlineMs(at, 17, at))
        assertEquals("19: ready 7 s in, so 22 s", at + 22_000, DashReadiness.deadlineMs(at, 19, at))
        assertEquals("22: ready 4 s in, so 19 s", at + 19_000, DashReadiness.deadlineMs(at, 22, at))
        assertEquals("12: ready 14 s in, so 29 s", at + 29_000, DashReadiness.deadlineMs(at, 12, at))
        assertEquals("no counter seen: the old 15 s", at + 15_000, DashReadiness.deadlineMs(at, null, 0))
    }

    @Test
    fun `a very young dash does not stretch the time past the ceiling`() {
        val at = 0L
        assertEquals(30_000L, DashReadiness.deadlineMs(at, 0, at)) // ready 26 s in, 41 s, capped at 30 s
        assertEquals(30_000L, DashReadiness.CAP_MS)
    }

    @Test
    fun `while the dash is not ready the greeting goes again every 1 s, until the key shows`() {
        assertFalse(DashReadiness.resendDue(999, keySeen = false, resends = 0))
        assertTrue(DashReadiness.resendDue(1_000, keySeen = false, resends = 0))
        assertFalse("the key is there", DashReadiness.resendDue(10_000, keySeen = true, resends = 0))
        assertTrue(DashReadiness.resendDue(1_000, keySeen = false, resends = DashReadiness.MAX_WAIT_RESENDS - 1))
        assertFalse(DashReadiness.resendDue(1_000, keySeen = false, resends = DashReadiness.MAX_WAIT_RESENDS))
    }

    @Test
    fun `the pace is 1 s and the count of greetings covers the same 28 s window as 14 greetings every 2 s did`() {
        assertEquals(1_000L, DashReadiness.WAIT_RESEND_MS)
        assertEquals(28, DashReadiness.MAX_WAIT_RESENDS)
        assertEquals("the same window as before (14 x 2 s)", 14 * 2_000L, DashReadiness.MAX_WAIT_RESENDS * DashReadiness.WAIT_RESEND_MS)
        // The longest wait there is: a dash first seen at 0 s is ready at 26 s; its greetings (25 of them, at 1 s ... 25 s) fit in the count with room.
        val waiting = greetings(0).filter { it < DashReadiness.READY_COUNT * 1_000L }
        assertEquals((1..25).map { it * 1_000L }, waiting)
        assertTrue(waiting.size <= DashReadiness.MAX_WAIT_RESENDS)
    }

    @Test
    fun `a dash 5 s old is greeted every 1 s until it is ready at 21 s, once more at that moment, then every 3 s`() {
        val went = greetings(5)
        assertEquals((1..20).map { it * 1_000L }, went.filter { it < 21_000 })
        assertTrue("the greeting of the moment it is ready: $went", 21_000L in went)
        assertEquals(listOf(24_000L, 27_000L, 30_000L), went.filter { it > 21_000 })
    }

    @Test
    fun `once the key was seen nothing is greeted again, at any age of the dash`() {
        for (first in listOf(0, 5, 12, 25, 26, 40, null)) {
            val greeting = DashReadiness.Greeting(patientEveryMs = 3_000, patientMax = 4)
            for (t in 100L..40_000L step 100) {
                assertFalse("age $first at $t ms", greeting.due(first, 0, t, sinceLastBurstMs = t, keySeen = true))
            }
        }
    }

    @Test
    fun `the greetings stop at the first moment the key shows, in the middle of the wait`() {
        val greeting = DashReadiness.Greeting(patientEveryMs = 3_000, patientMax = 4)
        var last = 0L
        val went = mutableListOf<Long>()
        for (t in 100L..30_000L step 100) {
            val keySeen = t >= 3_500 // the dash answers after the third resent greeting
            if (greeting.due(5, 0, t, t - last, keySeen)) { went += t; last = t }
        }
        assertEquals(listOf(1_000L, 2_000L, 3_000L), went)
    }

    @Test
    fun `the loop of the session sends no greeting once the auth is confirmed or the key was seen`() {
        val session = File("src/main/java/com/reau/app/dash/DashSession.kt").readText()
        val loop = session.substringAfter("val greeting = DashReadiness.Greeting(").substringBefore("if (!authConfirmed) {")
        assertTrue("the wait ends with the auth", "while (!authConfirmed &&" in loop)
        assertTrue("a greeting is not sent after the auth was confirmed", "if (!authConfirmed && (onNotice || due))" in loop)
        assertTrue("the key seen is told to the rule", "auth?.keySeen == true)" in loop && "greeting.due(first, firstHeartbeatAtMs, now, now - lastBurstMs, auth?.keySeen == true)" in loop)
        assertTrue("the 10 s mark does not ask either with the key seen", "&& auth?.keySeen != true" in loop)
    }

    /** The greetings of one entry, 100 ms at a time, as the loop of DashSession drives them; the moments (ms) they went. */
    private fun greetings(first: Int?, untilMs: Long = DashReadiness.deadlineMs(0, first, 0)): List<Long> {
        val greeting = DashReadiness.Greeting(patientEveryMs = 3_000, patientMax = 4)
        val went = mutableListOf<Long>()
        var last = 0L
        var t = 0L
        while (t < untilMs) {
            t += 100
            if (greeting.due(first, 0, t, t - last, keySeen = false)) {
                went += t
                last = t
            }
        }
        return went
    }

    @Test
    fun `a dash with a counter of 12 is greeted every 1 s while it is not ready and once more the moment it is ready at 14 s`() {
        val went = greetings(12)
        assertEquals((1..13).map { it * 1_000L }, went.filter { it < 14_000 })
        assertTrue("the greeting right when it is ready: $went", 14_000L in went)
        // then the patient pace from there, every 3 s; the greeting of the moment it became ready is the first of its four
        assertEquals(listOf(17_000L, 20_000L, 23_000L), went.filter { it > 14_000 })
    }

    @Test
    fun `the first run of this rule ran out of greetings just when the dash became ready`() {
        // 6 greetings had gone by 12 s; the old counting (all of them against the patient cap) left none after the dash was ready
        val went = greetings(12)
        assertTrue("at least one greeting after 14 s: $went", went.any { it >= 14_000 })
    }

    @Test
    fun `a dash already ready is greeted at the old pace from the entry, 3 s and four times`() {
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L), greetings(32, untilMs = 15_000))
        assertEquals("a dash whose counter was not seen: the same", listOf(3_000L, 6_000L, 9_000L, 12_000L), greetings(null, untilMs = 15_000))
    }

    @Test
    fun `the greeting of the moment the dash is ready says so, and the patient count starts from there`() {
        val greeting = DashReadiness.Greeting(3_000, 4)
        var last = 0L
        val moments = mutableListOf<Long>()
        var patient = 0
        for (now in 100L..30_000L step 100) {
            if (greeting.due(12, 0, now, now - last, keySeen = false)) {
                last = now
                if (greeting.readyMoment) moments += now else if (now > 14_000) patient = greeting.patientSent
            }
        }
        assertEquals("only one greeting is the one of the moment: $moments", listOf(14_000L), moments)
        assertEquals("the one of the moment is the first of the four patient ones; the waiting ones do not count", 4, patient)
        assertTrue(greeting.total > greeting.patientSent)
    }

    @Test
    fun `the counter wraps at 256 in the real heartbeat, and the age line says a low counter may be an old dash`() {
        val prefix = byteArrayOf(0x4B, 0x31, 0x47)
        // From the log of 20/10/2026 (the high byte stays 0): 0xE9, then 0x07 thirty seconds later.
        assertEquals(0xE9, DashReadiness.counterOf(prefix + byteArrayOf(0x00, 0xE9.toByte())))
        assertEquals(0x07, DashReadiness.counterOf(prefix + byteArrayOf(0x00, 0x07)))
        assertFalse("a dash on for 256 s or more reads young: known, and costs only greetings", DashReadiness.ready(0x07, 0, 0))
        assertTrue(DashReadiness.ageLine(7).contains("wraps at 256"))
        assertFalse("a counter from 26 up is not doubted", DashReadiness.ageLine(30).contains("wraps"))
        assertEquals("dash heartbeat age 17 s at the first packet (the counter wraps at 256: it may also be a dash on for 256 s or more)", DashReadiness.ageLine(17))
    }

    @Test
    fun `once the key is there nothing is sent`() {
        val greeting = DashReadiness.Greeting(3_000, 4)
        assertFalse(greeting.due(12, 0, 5_000, 5_000, keySeen = true))
        assertFalse(greeting.due(12, 0, 15_000, 15_000, keySeen = true))
    }

    @Test
    fun `the two lines of the entry say the dash's age and what the key cost, with no payload`() {
        assertTrue(DashReadiness.ageLine(17).startsWith("dash heartbeat age 17 s at the first packet"))
        assertEquals("key arrived 3.2 s after the first greeting, after 1 resend", DashReadiness.keyLine(3_200, 1))
        assertEquals("key arrived 0.1 s after the first greeting, after 0 resends", DashReadiness.keyLine(120, 0))
        assertEquals("key arrived 14.3 s after the first greeting, after 6 resends", DashReadiness.keyLine(14_300, 6))
    }
}
