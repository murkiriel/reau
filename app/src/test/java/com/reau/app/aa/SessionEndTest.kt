package com.reau.app.aa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the receiver does when a session ends, in its link with the USB (C15 of the pending list: only an instrumented test could reach it): an end that
 * follows a change of the USB is said, dialled again at once and tells the music to wait for the next session; any other end waits as always; and the
 * third unanswered handshake in a row is not a drop at all. The clock is a counter, as in [UsbSessionEndTest].
 */
class SessionEndTest {
    private var now = 10_000L
    private val usb = UsbSessionEnd({ now })
    private val said = mutableListOf<String>()
    private val music = mutableListOf<Boolean>()

    private fun settle(streak: HandshakeStreak, handshook: Boolean) =
        SessionEnd.settle(usb, streak, handshook, say = { said += it }, musicEnded = { music += it })

    private fun pauses(ending: UsbSessionEnd.Ending, normalMs: Long = 1_000): List<Long> =
        mutableListOf<Long>().also { slept -> ending.pauseAfterDrop(normalMs) { slept += it } }

    @Test
    fun `an answered session that ended with the USB is said, tells the music, and the next dial waits no time`() {
        usb.changed()
        now += 47
        val ending = settle(HandshakeStreak(), handshook = true)
        assertNotNull(ending)
        assertEquals(listOf(UsbSessionEnd.line(47)), said)
        assertEquals(listOf(true), music)
        assertEquals("no pause at all", emptyList<Long>(), pauses(ending!!))
    }

    @Test
    fun `an answered session that ended with no USB change tells the music it was not the USB and waits the usual second`() {
        val ending = settle(HandshakeStreak(), handshook = true)
        assertEquals(emptyList<String>(), said)
        assertEquals(listOf(false), music)
        assertEquals(listOf(1_000L), pauses(ending!!))
    }

    @Test
    fun `a session whose handshake was never answered does not touch the music, and its USB end is still said and used up`() {
        usb.changed()
        now += 30
        val streak = HandshakeStreak()
        val ending = settle(streak, handshook = false)
        assertEquals("not the music's business: nothing was playing through a session that never answered", emptyList<Boolean>(), music)
        assertEquals(listOf(UsbSessionEnd.line(30)), said)
        assertEquals(emptyList<Long>(), pauses(ending!!))
        assertEquals(1, streak.inARow)
        now += 10
        assertEquals("one change explains one end", listOf(1_000L), pauses(settle(streak, handshook = false)!!))
    }

    @Test
    fun `the third unanswered handshake in a row is no drop, whatever the USB did, and an answered one starts the count again`() {
        val streak = HandshakeStreak()
        assertNotNull(settle(streak, handshook = false))
        assertNotNull(settle(streak, handshook = false))
        usb.changed()
        now += 5
        assertNull("three in a row: Android Auto does not answer", settle(streak, handshook = false))
        assertEquals("the USB end was asked all the same", listOf(UsbSessionEnd.line(5)), said)
        assertNotNull("an answered one is a drop again", settle(streak, handshook = true))
        assertEquals(0, streak.inARow)
        assertNotNull(settle(streak, handshook = false))
    }

    @Test
    fun `the receiver settles through this and pauses with what it returns, only while it is the current one`() {
        val source = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        val after = source.substringAfter("private fun afterSession(").substringBefore("/** Waits [ms] between dials")
        val guard = after.indexOf("if (!isCurrent(mine)) return")
        val settle = after.indexOf("SessionEnd.settle(usb, unanswered, handshook,")
        assertTrue("the guard comes first", guard in 0 until settle)
        assertTrue(after, "musicEnded = { musicResume.sessionEnded(it, playingAtEnd) }" in after)
        assertTrue(after, "drop.pauseAfterDrop(timing.afterDropMs) { Thread.sleep(it) }" in after)
        assertFalse("the receiver no longer asks the USB by itself", "usb.noteEnd" in after || "unanswered.after(" in after)
        assertTrue("the receiver's usb is the one of its options", "private val usb = options.usb" in source)
    }
}
