package com.reau.app.aa

import com.reau.app.data.PhoneNow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines around the server's start walk: what the phone was doing, what the screen showed after the click, which window came up. */
class ServerTrailTest {
    private val texts = ServerTexts.table()

    @After
    fun reset() = PhoneNow.testOnlyReset()

    @Test
    fun `the phone line uses the words of the device line, and a question mark for what is not known`() {
        assertEquals("saver=on charging=no screen=on", ServerTrail.phone(saver = true, charging = false, screenOn = true))
        assertEquals("saver=off charging=yes screen=off", ServerTrail.phone(saver = false, charging = true, screenOn = false))
        assertEquals("saver=? charging=? screen=?", ServerTrail.phone(null, null, null))
    }

    @Test
    fun `PhoneNow gives the charger and the screen it last heard`() {
        assertNull(PhoneNow.chargingNow())
        PhoneNow.update(charging = true, screenOn = false, dozing = null)
        assertEquals(true, PhoneNow.chargingNow())
        assertEquals(false, PhoneNow.screenOnNow())
    }

    @Test
    fun `after the click line says in front or not, when, how many nodes, and what the screen said`() {
        assertEquals("after the click: AA in front=yes at +300 ms; nodes=12; known=starting", ServerTrail.afterClick(true, 300, 12, ServerTrail.Known.STARTING))
        assertEquals("after the click: AA in front=no at +310 ms; nodes=0; known=none", ServerTrail.afterClick(false, 310, 0, ServerTrail.Known.NONE))
    }

    @Test
    fun `known is read only from the texts the table already has`() {
        val stop = texts.stop.first()
        val starting = texts.notificationText.first()
        assertEquals(ServerTrail.Known.STARTED, ServerTrail.known(texts, listOf("something", stop)))
        assertEquals(ServerTrail.Known.STARTING, ServerTrail.known(texts, listOf(null, starting)))
        assertEquals(ServerTrail.Known.NONE, ServerTrail.known(texts, listOf("Settings", "a title that only this phone has")))
        assertEquals(ServerTrail.Known.NONE, ServerTrail.known(texts, emptyList()))
    }

    @Test
    fun `a window line has the short class and never a title`() {
        assertEquals("DefaultSettingsActivity", ServerTrail.shortClass("com.google.android.projection.gearhead.settings.DefaultSettingsActivity"))
        assertEquals("?", ServerTrail.shortClass(null))
        assertEquals("?", ServerTrail.shortClass(""))
        assertEquals("aa-window: DefaultSettingsActivity resumed +1500 ms", ServerTrail.window("DefaultSettingsActivity", 1_500))
    }

    @Test
    fun `at most twenty window lines a projection, and a new projection starts the count again`() {
        val ring = ServerTrail.Windows()
        val lines = (1..30).map { ring.next("a.b.Window$it", it * 10L) }
        assertEquals(ServerTrail.MAX_WINDOWS, lines.count { it != null })
        assertTrue("the first twenty are kept, in order", lines.take(20).all { it != null } && lines.drop(20).all { it == null })
        assertEquals("aa-window: Window1 resumed +10 ms", lines.first())
        ring.reset()
        assertNotNull(ring.next("a.b.After", 5))
    }
}
