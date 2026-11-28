package com.reau.app.data

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import com.reau.app.ui.KeepAliveGuide
import com.reau.app.ui.KeepAliveGuide.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the one notice before the first ride is shown (26/11/2026, A3 of the Xiaomi and realme plan): only on the two families of makers, only after the phone
 * has joined the dash, once, and once more after the system closed REau in a ride. The wiring (the card, the activity, the log line of the answer) is in
 * KeepAliveNoticeIsWiredTest.
 */
class KeepAliveNoticeTest {
    private val closedAt = 5_000L

    private fun due(eligible: Boolean = true, paired: Boolean = true, answeredAt: Long = 0L, lastClosed: Long = 0L, answeredClosed: Long = 0L) =
        KeepAliveNotice.due(eligible, paired, answeredAt, lastClosed, answeredClosed)

    // -- the maker -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `only the two families with steps of their own have a notice, any other phone is left as it was`() {
        assertTrue(KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf("Xiaomi", "Redmi")))
        assertTrue(KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf("Xiaomi", "POCO")))
        assertTrue(KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf("realme", "realme")))
        assertTrue(KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf("OPPO", "OPPO")))
        assertTrue(KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf("OnePlus", "OnePlus")))
        for (maker in listOf("samsung", "Google", "motorola", "vivo", "HUAWEI")) assertFalse(maker, KeepAliveGuide.hasNotice(KeepAliveGuide.brandOf(maker, maker)))
        assertFalse(KeepAliveGuide.hasNotice(Brand.OTHER))
        assertFalse("another maker is never due, whatever else is true", due(eligible = false))
        assertFalse(due(eligible = false, lastClosed = closedAt))
    }

    // -- after the first pairing, once ---------------------------------------------------------------------------------------------

    @Test
    fun `it is not due at the first opening, before the phone has joined the dash`() {
        assertFalse(due(paired = false))
    }

    @Test
    fun `it is due once the phone has joined the dash, and not answered yet`() {
        assertTrue(due(paired = true, answeredAt = 0L))
    }

    @Test
    fun `once answered it is not due again, joined or not, while the system has not closed REau since`() {
        assertFalse(due(answeredAt = 1_000L))
        assertFalse(due(answeredAt = 1_000L, lastClosed = closedAt, answeredClosed = closedAt))
        assertFalse("the dash forgotten and paired again changes nothing", due(paired = false, answeredAt = 1_000L))
    }

    @Test
    fun `a closing of the system told after the answer makes it due once more, and the answer after that quiets it`() {
        assertTrue(due(answeredAt = 1_000L, lastClosed = closedAt, answeredClosed = 0L))
        assertFalse("a closing the answer already knew of does not", due(answeredAt = 1_000L, lastClosed = 3_000L, answeredClosed = 3_000L))
        assertFalse(due(answeredAt = 9_000L, lastClosed = closedAt, answeredClosed = closedAt))
    }

    @Test
    fun `a closing alone, with the phone never paired, does not make it due`() {
        assertFalse(due(paired = false, lastClosed = closedAt))
    }

    @Test
    fun `a closing told before the notice ever showed does not matter, since it is due anyway until answered`() {
        assertTrue(due(lastClosed = closedAt))
    }

    // -- what is kept -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is kept until the rider answers`() {
        val book = KeepAliveNotice.Book(FakePrefs())
        assertEquals(0L, book.answeredAt())
        assertEquals(0L, book.answeredClosed())
    }

    @Test
    fun `the answer keeps its time and the last closing told then, and the next answer replaces them`() {
        val prefs = FakePrefs()
        KeepAliveNotice.Book(prefs).answer(1_000L, 0L)
        assertEquals(1_000L, KeepAliveNotice.Book(prefs).answeredAt())
        assertEquals(0L, KeepAliveNotice.Book(prefs).answeredClosed())
        KeepAliveNotice.Book(prefs).answer(9_000L, closedAt)
        assertEquals(9_000L to closedAt, KeepAliveNotice.Book(prefs).let { it.answeredAt() to it.answeredClosed() })
    }

    @Test
    fun `the whole story with what is kept, from the first pairing to a second closing`() {
        val book = KeepAliveNotice.Book(FakePrefs())
        fun now(paired: Boolean, lastClosed: Long) = KeepAliveNotice.due(true, paired, book.answeredAt(), lastClosed, book.answeredClosed())
        assertFalse("first opening, never paired", now(paired = false, lastClosed = 0L))
        assertTrue("first pairing", now(paired = true, lastClosed = 0L))
        book.answer(2_000L, 0L)
        assertFalse("answered", now(paired = true, lastClosed = 0L))
        assertTrue("the system closed REau in a ride", now(paired = true, lastClosed = closedAt))
        book.answer(6_000L, closedAt)
        assertFalse("answered again", now(paired = true, lastClosed = closedAt))
        assertTrue("and once more after another closing", now(paired = true, lastClosed = closedAt + 1))
    }

    @Test
    fun `the last closing is the newest told, dismissed or not`() {
        val prefs = FakePrefs()
        val book = SystemClosedRide.Book(prefs)
        assertEquals(0L, book.notedAt())
        book.note(closedAt)
        assertEquals(closedAt, book.notedAt())
        book.dismiss()
        assertEquals("dismissing the card does not forget that the closing happened", closedAt, SystemClosedRide.Book(prefs).notedAt())
    }
}
