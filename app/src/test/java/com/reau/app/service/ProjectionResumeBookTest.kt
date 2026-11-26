package com.reau.app.service

import android.content.SharedPreferences
import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [ProjectionResumeBook] keeps on the phone: one mark, written when the rider switches on, renewed once a minute, erased when the projection is over. */
class ProjectionResumeBookTest {
    /** The fake preferences, counting how each edit was ended: `commit` (on the disk when the call returns) or `apply` (a moment later). */
    private class CountingPrefs(private val inner: FakePrefs = FakePrefs()) : SharedPreferences by inner {
        var commits = 0
        var applies = 0
        val map get() = inner.map

        override fun edit(): SharedPreferences.Editor {
            val real = inner.edit()
            return object : SharedPreferences.Editor by real {
                override fun commit(): Boolean = real.commit().also { commits++ }

                override fun apply() {
                    applies++
                    real.apply()
                }
            }
        }
    }

    private val prefs = CountingPrefs()
    private val book = ProjectionResumeBook(prefs)
    private val t0 = 1_000_000_000_000L
    private val minute = 60_000L

    @Test
    fun `nothing is kept before the first Ligar`() {
        assertNull(book.read())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `Ligar keeps the instant and the mode, and nothing else`() {
        book.begin(t0, card = true)
        val mark = book.read()!!
        assertEquals(t0, mark.lastSeenMs)
        assertTrue(mark.card)
        assertEquals(emptyList<Long>(), mark.resumedAtMs)
        assertEquals(0L, mark.removedAtMs)
        assertEquals("instants and a mode: no name, no place, no network", setOf("seen_at", "card", "dash_up_at"), prefs.map.keys)
    }

    @Test
    fun `a new Ligar starts over, with no resume counted and no removal remembered`() {
        book.begin(t0, card = false)
        book.recordResume(t0 + minute, listOf(t0 + minute))
        book.taskRemoved(t0 + 2 * minute)
        book.begin(t0 + 3 * minute, card = false)
        val mark = book.read()!!
        assertEquals(t0 + 3 * minute, mark.lastSeenMs)
        assertEquals(emptyList<Long>(), mark.resumedAtMs)
        assertEquals(0L, mark.removedAtMs)
    }

    @Test
    fun `the beat renews the instant at most once a minute`() {
        book.begin(t0, card = false)
        assertFalse("the Ligar was the last write", book.beat(t0 + 5_000L, dashUp = true))
        assertFalse(book.beat(t0 + minute - 1, dashUp = true))
        assertEquals(t0, book.read()!!.lastSeenMs)
        assertTrue(book.beat(t0 + minute, dashUp = true))
        assertEquals(t0 + minute, book.read()!!.lastSeenMs)
        assertFalse(book.beat(t0 + minute + 5_000L, dashUp = true))
        assertTrue(book.beat(t0 + 2 * minute + 5_000L, dashUp = true))
        assertEquals(t0 + 2 * minute + 5_000L, book.read()!!.lastSeenMs)
    }

    @Test
    fun `a beat after the mark was erased does not bring it back`() {
        book.begin(t0, card = false)
        book.erase()
        assertFalse(book.beat(t0 + 10 * minute, dashUp = true))
        assertNull(book.read())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `the first beat of a new process writes at once, whatever the old instant`() {
        book.begin(t0, card = false)
        val fresh = ProjectionResumeBook(prefs)
        assertTrue(fresh.beat(t0 + 1_000L, dashUp = true))
        assertEquals(t0 + 1_000L, fresh.read()!!.lastSeenMs)
    }

    @Test
    fun `a resume keeps its window and renews the instant, and the mode stays`() {
        book.begin(t0, card = true)
        book.recordResume(t0 + 10 * minute, listOf(t0 + 4 * minute, t0 + 10 * minute))
        val mark = book.read()!!
        assertEquals(t0 + 10 * minute, mark.lastSeenMs)
        assertEquals(listOf(t0 + 4 * minute, t0 + 10 * minute), mark.resumedAtMs)
        assertTrue(mark.card)
    }

    @Test
    fun `taking the app off the recent apps is remembered only for a projection that is on`() {
        book.taskRemoved(t0)
        assertNull("no mark made of it", book.read())
        assertTrue(prefs.map.isEmpty())
        book.begin(t0 + minute, card = false)
        book.taskRemoved(t0 + 2 * minute)
        assertEquals(t0 + 2 * minute, book.read()!!.removedAtMs)
    }

    @Test
    fun `Ligar counts the dash as seen at that instant, and a beat with the dash up renews it with the next write`() {
        book.begin(t0, card = false)
        assertEquals(t0, book.read()!!.dashUpAtMs)
        assertFalse(book.beat(t0 + 20_000L, dashUp = true))
        assertEquals("not on the disk before the minute is over", t0, book.read()!!.dashUpAtMs)
        assertFalse(book.beat(t0 + 40_000L, dashUp = false))
        assertTrue(book.beat(t0 + minute, dashUp = false))
        val mark = book.read()!!
        assertEquals("the instant of the last sign of life", t0 + minute, mark.lastSeenMs)
        assertEquals("the last instant the dash was up, not the one of the write", t0 + 20_000L, mark.dashUpAtMs)
    }

    @Test
    fun `a dash that stays down keeps its old instant while the sign of life goes on`() {
        book.begin(t0, card = false)
        assertTrue(book.beat(t0 + minute, dashUp = false))
        assertTrue(book.beat(t0 + 10 * minute, dashUp = false))
        val mark = book.read()!!
        assertEquals(t0 + 10 * minute, mark.lastSeenMs)
        assertEquals(t0, mark.dashUpAtMs)
        assertEquals("the verdict follows: gone for 10 min at the last sign of life", 10 * minute, mark.lastSeenMs - mark.dashUpAtMs)
    }

    @Test
    fun `a resume starts the wait for the dash over and writes its count at once`() {
        book.begin(t0, card = false)
        assertTrue(book.beat(t0 + minute, dashUp = true))
        book.recordResume(t0 + 5 * minute, listOf(t0 + 5 * minute))
        assertEquals(t0 + 5 * minute, book.read()!!.dashUpAtMs)
    }

    @Test
    fun `the count of a resume is committed, so that a kill a moment later cannot lose it, and the beat is only applied`() {
        book.begin(t0, card = false)
        val appliesBefore = prefs.applies
        book.recordResume(t0 + minute, listOf(t0 + minute))
        assertEquals("one commit for the count", 1, prefs.commits)
        assertEquals("and no apply left behind", appliesBefore, prefs.applies)
        assertTrue(book.beat(t0 + 2 * minute, dashUp = true))
        assertEquals("a beat is not a commit", 1, prefs.commits)
        assertEquals(appliesBefore + 1, prefs.applies)
    }

    @Test
    fun `reopening the app takes away the removal from the recent apps, and only that`() {
        book.reopened()
        assertNull("no mark made of it", book.read())
        assertTrue(prefs.map.isEmpty())
        book.begin(t0, card = true)
        book.recordResume(t0 + minute, listOf(t0 + minute))
        book.taskRemoved(t0 + 2 * minute)
        assertEquals(t0 + 2 * minute, book.read()!!.removedAtMs)
        book.reopened()
        val mark = book.read()!!
        assertEquals(0L, mark.removedAtMs)
        assertEquals(listOf(t0 + minute), mark.resumedAtMs)
        assertTrue(mark.card)
    }

    @Test
    fun `erase takes away everything, the count too, so the next Ligar is a new ride`() {
        book.begin(t0, card = false)
        book.recordResume(t0, listOf(t0))
        book.taskRemoved(t0)
        book.erase()
        assertNull(book.read())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `a mark without a usable instant, or with a broken list, reads as safely as it can`() {
        prefs.edit().putLong("seen_at", 0L).apply()
        assertNull("no instant, no mark", book.read())
        prefs.edit().putLong("seen_at", t0).putString("resumed_at", "12,x,,34, 5").apply()
        assertEquals("the numbers it can read", listOf(12L, 34L), book.read()!!.resumedAtMs)
        assertNotNull(book.read())
    }
}
