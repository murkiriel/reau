package com.reau.app.data

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The system closing REau in the middle of a ride (plan 34, item 5): the rule over what Android kept of the exit, which exit is picked, how its time is
 * written and what is kept so the notice shows once and until dismissed. The examples are the exits of two real rides (a Xiaomi and a realme phone, 25/11/2026)
 * and the exits that must stay quiet. The line of the ride log and the screens are in SystemClosedRideIsWiredTest.
 */
class SystemClosedRideTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    // Android's codes (ApplicationExitInfo.REASON_*, RunningAppProcessInfo.IMPORTANCE_*).
    private val exitSelf = 1
    private val lowMemory = 3
    private val crash = 4
    private val nativeCrash = 5
    private val anr = 6
    private val permissionChange = 8
    private val userRequested = 10
    private val other = 13
    private val packageUpdated = 16
    private val foregroundService = 125
    private val cached = 400

    private fun decide(reason: Int, importance: Int, description: String? = null, at: Long = now - 30 * minute) =
        SystemClosedRide.decide(reason, importance, description, at, now)

    private fun exit(at: Long, reason: Int, importance: Int, description: String? = null, pid: Int = 1) =
        PastExit(at, "com.reau.app", pid, reason, 0, importance, 0, 0, description)

    // -- the rule ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the Xiaomi exit of the ride (user requested, foreground service, the system loading too high) is told`() {
        val description = "[FORCE STOP] stop com.reau.app due to UserDefined:The system loading is too high and ANR is occurring."
        assertTrue(decide(userRequested, foregroundService, description))
    }

    @Test
    fun `the realme exit of the ride (other, foreground service, o-kill) is told`() {
        assertTrue(decide(other, foregroundService, "[UNKNOWN] o-kill(6)"))
    }

    @Test
    fun `the exits of an app that was only cached are not told, whatever the reason`() {
        assertFalse("the rider dragged the app out of the recent apps", decide(userRequested, cached, "[REMOVE TASK] remove task"))
        assertFalse(decide(userRequested, cached, "SwipeUpClean"))
        assertFalse(decide(other, cached, "normal_mem_pressure"))
        assertFalse(decide(lowMemory, cached))
        assertFalse(decide(other, cached, "[UNKNOWN] o-kill(6)"))
    }

    @Test
    fun `low memory with the projection on is told`() {
        assertTrue(decide(lowMemory, foregroundService))
    }

    @Test
    fun `the importance of the foreground service and anything better counts, anything worse does not`() {
        assertTrue(decide(other, 100))
        assertTrue(decide(other, 125))
        assertFalse(decide(other, 126))
        assertFalse(decide(other, 230))
        assertFalse(decide(other, 300))
        assertFalse(decide(other, cached))
        assertFalse("an importance Android never gave", decide(other, 0))
    }

    @Test
    fun `the exits that are not the system's work are quiet even with the projection on`() {
        assertFalse("the app's own exit, which is how the Desligar button ends the process", decide(exitSelf, foregroundService))
        assertFalse(decide(crash, foregroundService))
        assertFalse(decide(nativeCrash, foregroundService))
        assertFalse(decide(anr, foregroundService))
        assertFalse(decide(permissionChange, foregroundService))
        assertFalse("the rider installing a new version", decide(packageUpdated, foregroundService))
        assertFalse("unknown", decide(0, foregroundService))
    }

    @Test
    fun `a foreground service exit whose text says the rider removed the app is not told, in any case of letters`() {
        assertFalse(decide(userRequested, foregroundService, "[REMOVE TASK] remove task"))
        assertFalse(decide(userRequested, foregroundService, "[Remove Task] x"))
        assertFalse(decide(userRequested, foregroundService, "SwipeUpClean"))
        assertFalse(decide(userRequested, foregroundService, "swipeupclean by launcher"))
    }

    @Test
    fun `no description at all is still told`() {
        assertTrue(decide(other, foregroundService, null))
        assertTrue(decide(other, foregroundService, ""))
    }

    @Test
    fun `an exit older than three days or in the future is not told, one just inside is`() {
        assertTrue(SystemClosedRide.decide(other, foregroundService, null, now - SystemClosedRide.MAX_AGE_MS, now))
        assertFalse(SystemClosedRide.decide(other, foregroundService, null, now - SystemClosedRide.MAX_AGE_MS - 1, now))
        assertTrue(SystemClosedRide.decide(other, foregroundService, null, now, now))
        assertFalse(SystemClosedRide.decide(other, foregroundService, null, now + 1, now))
        assertEquals(3 * 24 * 60 * minute, SystemClosedRide.MAX_AGE_MS)
    }

    // -- which exit ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the newest exit that the rule takes is picked, skipping the quiet ones that came after it`() {
        val killed = exit(now - 5 * 60 * minute, other, foregroundService, "[UNKNOWN] o-kill(6)", pid = 3)
        val exits = listOf(
            exit(now - 9 * 60 * minute, userRequested, foregroundService, "[FORCE STOP] x", pid = 1),
            killed,
            exit(now - 2 * 60 * minute, userRequested, cached, "[REMOVE TASK] remove task", pid = 4),
            exit(now - 1 * 60 * minute, exitSelf, cached, null, pid = 5),
        )
        assertEquals(killed, SystemClosedRide.pick(exits, 0L, now))
    }

    @Test
    fun `nothing is picked from exits that are all quiet, or from none`() {
        assertNull(SystemClosedRide.pick(emptyList(), 0L, now))
        assertNull(SystemClosedRide.pick(listOf(exit(now - minute, lowMemory, cached), exit(now - 2 * minute, exitSelf, foregroundService)), 0L, now))
    }

    @Test
    fun `an exit already told or dismissed is not picked again, a newer one is`() {
        val old = exit(now - 60 * minute, other, foregroundService)
        val newer = exit(now - 10 * minute, other, foregroundService)
        assertNull(SystemClosedRide.pick(listOf(old), old.timestampMs, now))
        assertNull(SystemClosedRide.pick(listOf(old), old.timestampMs + 1, now))
        assertEquals(newer, SystemClosedRide.pick(listOf(old, newer), old.timestampMs, now))
    }

    // -- the time -----------------------------------------------------------------------------------------------------------------

    private val zone = TimeZone.getTimeZone("UTC")
    private val brazil = Locale.forLanguageTag("pt-BR")
    private val exitAt = 1795618116000L // 2026-11-25 14:48:36 UTC, in the afternoon of the ride

    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `the time of an exit of today is the hour and the minutes alone`() {
        assertEquals("14:48", SystemClosedRide.stamp(exitAt, exitAt, brazil, zone))
        assertEquals("14:48", SystemClosedRide.stamp(exitAt, exitAt + 5 * hour, brazil, zone))
    }

    @Test
    fun `the time of an exit of another day comes with the day`() {
        val yesterday = SystemClosedRide.stamp(exitAt, exitAt + day, brazil, zone)
        assertTrue(yesterday, yesterday.startsWith("25/11") && yesterday.endsWith(" 14:48"))
        val lastYear = SystemClosedRide.stamp(exitAt, exitAt + 365 * day, brazil, zone)
        assertTrue("the same day of the year, another year: $lastYear", lastYear.endsWith(" 14:48") && lastYear.length > 5)
    }

    @Test
    fun `the day is the one of the phone's time zone, not of UTC`() {
        // 14:48 UTC is 11:48 in Sao Paulo; five hours later it is still the same day there, and 11:48 + 13 hours is the next one.
        val saoPaulo = TimeZone.getTimeZone("America/Sao_Paulo")
        assertEquals("11:48", SystemClosedRide.stamp(exitAt, exitAt + 5 * hour, brazil, saoPaulo))
        assertTrue(SystemClosedRide.stamp(exitAt, exitAt + 13 * hour, brazil, saoPaulo).startsWith("25/11"))
    }

    // -- what is kept -------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is kept at first, and nothing is told`() {
        val book = SystemClosedRide.Book(FakePrefs())
        assertNull(book.pendingAt())
        assertEquals(0L, book.toldUpTo())
    }

    @Test
    fun `an exit noted is pending until dismissed, and noting it again says it is not new`() {
        val book = SystemClosedRide.Book(FakePrefs())
        assertTrue(book.note(5_000L))
        assertEquals(5_000L, book.pendingAt())
        assertFalse("the same exit at the next opening", book.note(5_000L))
        assertFalse("an older one", book.note(4_000L))
        assertEquals(5_000L, book.pendingAt())
    }

    @Test
    fun `dismissed, the notice is gone for good, and survives a new object over the same file`() {
        val prefs = FakePrefs()
        val book = SystemClosedRide.Book(prefs)
        book.note(5_000L)
        book.dismiss()
        assertNull(book.pendingAt())
        assertNull(SystemClosedRide.Book(prefs).pendingAt())
        assertFalse("the exit dismissed is not noted again", SystemClosedRide.Book(prefs).note(5_000L))
        assertEquals(5_000L, SystemClosedRide.Book(prefs).toldUpTo())
    }

    @Test
    fun `a newer exit after a dismissal is noted and pending again`() {
        val book = SystemClosedRide.Book(FakePrefs())
        book.note(5_000L)
        book.dismiss()
        assertTrue(book.note(9_000L))
        assertEquals(9_000L, book.pendingAt())
    }

    @Test
    fun `dismissing with nothing pending changes nothing`() {
        val prefs = FakePrefs()
        SystemClosedRide.Book(prefs).dismiss()
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `not dismissed, the notice is still pending at the next opening, and a second exit replaces the first`() {
        val prefs = FakePrefs()
        SystemClosedRide.Book(prefs).note(5_000L)
        assertEquals(5_000L, SystemClosedRide.Book(prefs).pendingAt())
        SystemClosedRide.Book(prefs).note(8_000L)
        assertEquals(8_000L, SystemClosedRide.Book(prefs).pendingAt())
    }
}
