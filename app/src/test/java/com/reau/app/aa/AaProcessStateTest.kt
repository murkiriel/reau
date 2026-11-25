package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ride log's line for a handshake Android Auto left unanswered (see [AaProcessState]): what REau could read of Android Auto and of the phone at
 * that moment, what it could not, and the care not to take "the list does not show it" for "it is not running" (Android shows an app only its own
 * processes). The reading is given; the Android calls that make it are not under test.
 */
class AaProcessStateTest {
    private val own = "com.reau.app"
    private val mute = AaProcessState.Mute(inARow = 3, mutedMs = 10_040, sinceGoodEndMs = 41_000, port = 5277, ownPackage = own)

    private val hidden = AaProcessState.Reading(
        installed = true, version = "18.0.664274-release", updatedAgoMs = 270_000_000, stopped = false, enabled = true, batteryExempt = false,
        backgroundMode = 1, processes = listOf(AaProcessState.Proc(own, 100), AaProcessState.Proc("$own:service", 125)),
        interactive = true, locked = false, dozing = false, powerSave = false,
    )

    private fun line(reading: AaProcessState.Reading = hidden, muted: AaProcessState.Mute = mute) = AaProcessState.line(muted, reading)

    @Test
    fun `the whole line for an Android Auto that Android hides from REau`() {
        assertEquals(
            "Android Auto answered nothing to the version request (handshake 3 in a row, 10.0 s after its server accepted the connection); " +
                "the head unit server on port 5277 accepted this dial; Android Auto 18.0.664274-release, updated 3 days ago, stopped state no, " +
                "battery optimization exempt no, background op ignored (restricted battery); processes: Android Auto's not listed, and the list " +
                "holds only REau's own 2 (Android hides the other apps': this does not say it is not running); " +
                "the last answered session ended 41 s ago; screen on, unlocked, doze off, battery saver off; " +
                "not readable without a permission: Android Auto's standby bucket, and whether Android froze or cached its process",
            line(),
        )
    }

    @Test
    fun `it says how many handshakes in a row and how long after the connection was accepted`() {
        val text = line(muted = mute.copy(inARow = 7, mutedMs = 31_900))
        assertTrue(text, "handshake 7 in a row, 31.9 s after its server accepted the connection" in text)
    }

    @Test
    fun `it says the server accepted this dial and on which port`() {
        val text = line(muted = mute.copy(port = 6000))
        assertTrue(text, "the head unit server on port 6000 accepted this dial" in text)
    }

    @Test
    fun `a process list holding only REau's own is not taken for Android Auto not running`() {
        val text = line()
        assertTrue(text, "the list holds only REau's own 2" in text)
        assertTrue(text, "this does not say it is not running" in text)
        assertFalse(text, "has none among" in text)
    }

    @Test
    fun `a list with other apps and none of Android Auto's says it has none`() {
        val text = AaProcessState.processes(
            listOf(AaProcessState.Proc(own, 100), AaProcessState.Proc("com.other.app", 400), AaProcessState.Proc("com.other.app:push", 400)), own,
        )
        assertEquals("processes: Android Auto has none among the 2 other processes listed", text)
    }

    @Test
    fun `REau's own secondary processes are not counted among the others`() {
        val text = AaProcessState.processes(listOf(AaProcessState.Proc(own, 100), AaProcessState.Proc("$own:service", 125)), own)
        assertTrue(text, "holds only REau's own 2" in text)
    }

    @Test
    fun `a process of Android Auto in the list is said with its importance, whichever process it is`() {
        val list = listOf(
            AaProcessState.Proc(own, 100),
            AaProcessState.Proc(AndroidAuto.PACKAGE + ":projection", 400),
            AaProcessState.Proc(AndroidAuto.PACKAGE, 125),
        )
        assertEquals("processes: projection cached, main foreground service", AaProcessState.processes(list, own))
    }

    @Test
    fun `a process whose name only begins like Android Auto's is not Android Auto's`() {
        val text = AaProcessState.processes(listOf(AaProcessState.Proc(AndroidAuto.PACKAGE + "x", 400)), own)
        assertEquals("processes: Android Auto has none among the 1 other processes listed", text)
    }

    @Test
    fun `a list that could not be asked is said so`() {
        assertEquals("processes: the list could not be asked", AaProcessState.processes(null, own))
    }

    @Test
    fun `the importance words`() {
        val words = mapOf(
            100 to "foreground", 125 to "foreground service", 150 to "top sleeping", 200 to "visible", 230 to "perceptible",
            270 to "can't save state", 300 to "service", 400 to "cached", 1000 to "gone", 321 to "importance 321",
        )
        for ((importance, word) in words) assertEquals(word, AaProcessState.importanceWords(importance))
    }

    @Test
    fun `the restricted battery setting is called by its name`() {
        assertEquals("ignored (restricted battery)", AaProcessState.backgroundWords(1))
        assertEquals("allowed", AaProcessState.backgroundWords(0))
        assertEquals("errored", AaProcessState.backgroundWords(2))
        assertEquals("default", AaProcessState.backgroundWords(3))
        assertEquals("foreground only", AaProcessState.backgroundWords(4))
        assertEquals("mode 9", AaProcessState.backgroundWords(9))
        assertEquals("not readable", AaProcessState.backgroundWords(null))
    }

    @Test
    fun `whatever could not be read is a question mark, never a guess`() {
        val text = line(AaProcessState.Reading(installed = true))
        assertTrue(text, "Android Auto (version not readable), stopped state ?, battery optimization exempt ?, background op not readable" in text)
        assertTrue(text, "processes: the list could not be asked" in text)
        assertTrue(text, "screen ?, ?, doze ?, battery saver ?" in text)
        assertFalse("no age out of nothing: $text", "updated" in text)
    }

    @Test
    fun `an Android Auto in the stopped state, disabled, or not installed is said so`() {
        val stopped = line(hidden.copy(stopped = true, enabled = false))
        assertTrue(stopped, "stopped state yes, disabled," in stopped)
        assertFalse(line(hidden), "disabled" in line(hidden))
        assertTrue(line(AaProcessState.Reading(installed = false)), "Android Auto is not installed;" in line(AaProcessState.Reading(installed = false)))
    }

    @Test
    fun `the phone's screen, lock, doze and battery saver are said as they are`() {
        val text = line(hidden.copy(interactive = false, locked = true, dozing = true, powerSave = true))
        assertTrue(text, "screen off, locked, doze on, battery saver on" in text)
    }

    @Test
    fun `the last answered session says how long ago, or that none is known`() {
        assertTrue(line(), "the last answered session ended 41 s ago" in line())
        val none = line(muted = mute.copy(sinceGoodEndMs = null))
        assertTrue(none, "no answered session known to end before" in none)
    }

    @Test
    fun `the line always names what stays unreadable without a permission`() {
        assertTrue(line().endsWith(AaProcessState.UNREADABLE))
        assertTrue(AaProcessState.UNREADABLE, "standby bucket" in AaProcessState.UNREADABLE && "froze" in AaProcessState.UNREADABLE)
    }

    @Test
    fun `times are said in the unit that reads easily`() {
        assertEquals("0 s", AaProcessState.agoWords(-5))
        assertEquals("119 s", AaProcessState.agoWords(119_999))
        assertEquals("2 min", AaProcessState.agoWords(120_000))
        assertEquals("119 min", AaProcessState.agoWords(119 * 60_000L))
        assertEquals("2 h", AaProcessState.agoWords(2 * 3_600_000L))
        assertEquals("47 h", AaProcessState.agoWords(47 * 3_600_000L))
        assertEquals("2 days", AaProcessState.agoWords(48 * 3_600_000L))
    }

    @Test
    fun `the reading is made and written once, with the numbers given`() {
        val said = mutableListOf<String>()
        AaProcessState.report(mute, read = { hidden }, say = { said += it })
        assertEquals(listOf(AaProcessState.line(mute, hidden)), said)
    }

    @Test
    fun `a reading that throws still writes the handshake, and says the state could not be read`() {
        val said = mutableListOf<String>()
        AaProcessState.report(mute, read = { throw IllegalArgumentException("no such op") }, say = { said += it })
        assertEquals(1, said.size)
        assertTrue(said[0], said[0].startsWith("Android Auto answered nothing to the version request (handshake 3 in a row, 10.0 s"))
        assertTrue(said[0], said[0].endsWith("its state could not be read (IllegalArgumentException)"))
    }

    // The receiver needs a Context and a live socket; where it calls this is read from the source, as the other "...IsWired" tests do.
    @Test
    fun `the receiver writes it for each unanswered handshake that was not cut short by a stop`() {
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertTrue("if (result == Handshake.MUTE && isCurrent(mine)) reportMute(unanswered.inARow + 1, acceptedAt)" in receiver)
        val body = receiver.substringAfter("private fun reportMute(").substringBefore("/** How a session's handshake went")
        assertTrue(body, "AaRestartGap.lastEndedAtMs" in body)
        assertTrue(body, "AaProcessState.Mute(inARow, now - acceptedAt, sinceGoodEnd, port, context.packageName)" in body)
        assertTrue(body, "AaProcessState.read(context)" in body)
        val stamp = receiver.indexOf("val acceptedAt = SystemClock.elapsedRealtime()")
        assertTrue("the connection is stamped before the session runs", stamp in 0 until receiver.indexOf("val result = runSession(mine, socket)"))
    }
}
