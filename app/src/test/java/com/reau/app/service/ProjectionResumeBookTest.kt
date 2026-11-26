package com.reau.app.service

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [ProjectionResumeBook] keeps on the phone: one mark, written when the rider switches on, renewed once a minute, erased when the projection is over. */
class ProjectionResumeBookTest {
    private val prefs = FakePrefs()
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
        assertEquals("an instant and a mode: no name, no place, no network", setOf("seen_at", "card"), prefs.map.keys)
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
        assertFalse("the Ligar was the last write", book.beat(t0 + 5_000L))
        assertFalse(book.beat(t0 + minute - 1))
        assertEquals(t0, book.read()!!.lastSeenMs)
        assertTrue(book.beat(t0 + minute))
        assertEquals(t0 + minute, book.read()!!.lastSeenMs)
        assertFalse(book.beat(t0 + minute + 5_000L))
        assertTrue(book.beat(t0 + 2 * minute + 5_000L))
        assertEquals(t0 + 2 * minute + 5_000L, book.read()!!.lastSeenMs)
    }

    @Test
    fun `a beat after the mark was erased does not bring it back`() {
        book.begin(t0, card = false)
        book.erase()
        assertFalse(book.beat(t0 + 10 * minute))
        assertNull(book.read())
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `the first beat of a new process writes at once, whatever the old instant`() {
        book.begin(t0, card = false)
        val fresh = ProjectionResumeBook(prefs)
        assertTrue(fresh.beat(t0 + 1_000L))
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
