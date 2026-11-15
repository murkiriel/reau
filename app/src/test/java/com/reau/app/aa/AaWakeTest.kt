package com.reau.app.aa

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.settings.REauSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Waking Android Auto before dialling it (experimental switch): the window of the last end of a session (more than 8 s and less than 45 min), the
 * order wake, wait, dial, once for each reason in a projection, the instant of the end kept across processes, and the switch. The dial is a real
 * connection to a fake head unit server on the loopback.
 */
class AaWakeTest {
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var enabled = true
    private var server: ServerSocket? = null
    private val accepted = Collections.synchronizedList(mutableListOf<Socket>())
    private var accepting: Thread? = null

    @Before
    fun fakeHeadUnitServer() {
        val listening = ServerSocket(0, 10, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = listening
        accepting = Thread {
            runCatching { while (true) accepted += listening.accept() }
        }.apply { isDaemon = true }.also { it.start() }
    }

    @After
    fun closeServer() {
        // The server first, and its thread let finish: a connection it is still taking must not land in the list while the list is walked.
        server?.close()
        accepting?.join(2_000)
        synchronized(accepted) { accepted.toList() }.forEach { runCatching { it.close() } }
        synchronized(dialled) { dialled.toList() }.forEach { runCatching { it.close() } }
        AaRestartGap.testOnlyNewProcess()
    }

    private fun gate(wake: () -> Unit = { events += "wake" }) =
        AaWake(enabled = { enabled }, wake = wake, pause = { events += "pause $it" }, say = { events += "say: $it" })

    private val dialled = Collections.synchronizedList(mutableListOf<Socket>())

    /** One dial of the projection, as the receiver makes it: [AaWake.dialing] around a real connection to the fake server. */
    private fun dial(gate: AaWake, nowMs: Long, endedAtMs: Long?): Socket = gate.dialing({ nowMs }, { endedAtMs }) {
        events += "dial"
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server!!.localPort).also { dialled += it }
    }

    private fun steps() = events.map { it.substringBefore(':') }.filterNot { it == "say" }

    private fun awaitAccepted(n: Int) {
        val limit = System.currentTimeMillis() + 3_000
        while (accepted.size < n && System.currentTimeMillis() < limit) Thread.sleep(10)
        assertEquals("connections the fake server took", n, accepted.size)
    }

    // ── the window ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the window is more than 8 seconds and less than 45 minutes since the end`() {
        val now = 10_000_000L
        assertFalse("no end known", AaWake.due(now, null))
        assertFalse("just ended", AaWake.due(now, now))
        assertFalse("7.9 s", AaWake.due(now, now - 7_900))
        assertFalse("exactly 8 s is not more than 8 s", AaWake.due(now, now - 8_000))
        assertTrue("8.001 s", AaWake.due(now, now - 8_001))
        assertTrue("20 minutes", AaWake.due(now, now - 20 * 60_000L))
        assertTrue("one millisecond short of 45 minutes", AaWake.due(now, now - (45 * 60_000L - 1)))
        assertFalse("exactly 45 minutes is not less than 45 minutes", AaWake.due(now, now - 45 * 60_000L))
        assertFalse("an hour", AaWake.due(now, now - 60 * 60_000L))
    }

    @Test
    fun `a clock that went backwards, the end after now, is no window`() {
        val now = 10_000_000L
        assertFalse(AaWake.due(now, now + 1))
        assertFalse(AaWake.due(now, now + 20 * 60_000L))
        assertFalse("elapsed realtime restarted at a boot", AaWake.due(5_000L, 3_000_000L))
    }

    // ── the order, and once ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `inside the window the wake comes first, then the wait of 700 ms, then the dial`() {
        val now = 10_000_000L
        dial(gate(), now, endedAtMs = now - 93_000)
        assertEquals(listOf("wake", "pause 700", "dial"), steps())
        awaitAccepted(1)
    }

    @Test
    fun `the wake is made once in a projection, whatever the dials after it`() {
        val now = 10_000_000L
        val waking = gate()
        repeat(3) { dial(waking, now, endedAtMs = now - 93_000) }
        assertEquals(listOf("wake", "pause 700", "dial", "dial", "dial"), steps())
        awaitAccepted(3)
    }

    @Test
    fun `outside the window there is no wake and the dial is as always`() {
        val now = 10_000_000L
        for ((name, ended) in listOf("just ended" to now - 5_000, "an hour ago" to now - 60 * 60_000L, "unknown" to null, "in the future" to now + 1_000L)) {
            events.clear()
            dial(gate(), now, ended)
            assertEquals(name, listOf("dial"), steps())
        }
        awaitAccepted(4)
    }

    @Test
    fun `with the switch off there is no wake, inside the window or after a mute negotiation`() {
        enabled = false
        val now = 10_000_000L
        val waking = gate()
        dial(waking, now, endedAtMs = now - 93_000)
        waking.muteNegotiation()
        dial(waking, now, endedAtMs = now - 93_000)
        assertEquals(listOf("dial", "dial"), steps())
    }

    @Test
    fun `the switch is read at each dial, not once`() {
        val now = 10_000_000L
        enabled = false
        val waking = gate()
        waking.muteNegotiation()
        dial(waking, now, endedAtMs = null)
        enabled = true
        dial(waking, now, endedAtMs = null)
        assertEquals("switched on after the first dial: the wake after the mute one is still due", listOf("dial", "wake", "pause 700", "dial"), steps())
    }

    // ── a negotiation that got no answer ───────────────────────────────────────────────────────────────────

    @Test
    fun `a mute negotiation makes one wake and one dial, and a second one nothing more`() {
        val now = 10_000_000L
        val waking = gate()
        dial(waking, now, endedAtMs = null)
        assertEquals(listOf("dial"), steps())
        events.clear()
        waking.muteNegotiation()
        dial(waking, now + 1_000, endedAtMs = null)
        assertEquals(listOf("wake", "pause 700", "dial"), steps())
        events.clear()
        waking.muteNegotiation()
        dial(waking, now + 8_000, endedAtMs = null)
        dial(waking, now + 16_000, endedAtMs = null)
        assertEquals("the cadence as it is: dials, no more wakes", listOf("dial", "dial"), steps())
        awaitAccepted(4)
    }

    @Test
    fun `a negotiation that got an answer, or none yet, wakes nothing`() {
        val now = 10_000_000L
        val waking = gate()
        dial(waking, now, endedAtMs = null)
        dial(waking, now + 1_000, endedAtMs = null)
        assertEquals(listOf("dial", "dial"), steps())
    }

    @Test
    fun `the wake before the first dial and the one after a mute negotiation are two, each once`() {
        val now = 10_000_000L
        val waking = gate()
        dial(waking, now, endedAtMs = now - 93_000)
        waking.muteNegotiation()
        dial(waking, now + 6_000, endedAtMs = now - 93_000)
        waking.muteNegotiation()
        dial(waking, now + 12_000, endedAtMs = now - 93_000)
        assertEquals(listOf("wake", "pause 700", "dial", "wake", "pause 700", "dial", "dial"), steps())
    }

    // ── the line in the ride log ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the log says why and how long ago the last session ended`() {
        val now = 10_000_000L
        val waking = gate()
        dial(waking, now, endedAtMs = now - 93_000)
        val first = events.first { it.startsWith("say: ") }
        assertTrue(first, "waking Android Auto before the first dial of this projection" in first)
        assertTrue(first, "ended 93 s ago" in first)
        assertTrue(first, "restricted battery" in first)
        events.clear()
        waking.muteNegotiation()
        dial(waking, now + 6_000, endedAtMs = now - 93_000)
        val second = events.first { it.startsWith("say: ") }
        assertTrue(second, "after a negotiation that got no answer" in second)
        assertTrue(second, "99 s ago" in second)
    }

    @Test
    fun `a mute negotiation with no end known says so`() {
        val waking = gate()
        waking.muteNegotiation()
        dial(waking, 10_000_000L, endedAtMs = null)
        val said = events.first { it.startsWith("say: ") }
        assertTrue(said, "not known" in said)
    }

    @Test
    fun `a wake that throws does not stop the dial, and says it failed`() {
        val now = 10_000_000L
        dial(gate(wake = { error("no activity") }), now, endedAtMs = now - 93_000)
        assertEquals(listOf("dial"), steps().filterNot { it == "pause 700" })
        assertTrue(events.toString(), events.any { it.startsWith("say: the wake failed (IllegalStateException)") })
        awaitAccepted(1)
    }

    // ── the instant of the end, kept ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the end of a session with no stop is kept, and the stop is left alone`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.sessionEnded(prefs, nowElapsedMs = 50_000, nowWallMs = 1_700_000_050_000)
        assertEquals(50_000L, AaRestartGap.lastEndedAtMs)
        assertNull("the second before a dial and the closing window count from REau's stop only", AaRestartGap.lastStoppedAtMs)
        assertFalse(prefs.contains("stopped_elapsed_ms"))
    }

    @Test
    fun `a stop is an end too`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.stopped(prefs, nowElapsedMs = 60_000, nowWallMs = 1_700_000_060_000)
        assertEquals(60_000L, AaRestartGap.lastEndedAtMs)
        assertEquals(60_000L, AaRestartGap.lastStoppedAtMs)
    }

    @Test
    fun `a process that comes back takes the end the one before kept, when the two clocks agree`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.sessionEnded(prefs, nowElapsedMs = 50_000, nowWallMs = 1_700_000_050_000)
        AaRestartGap.testOnlyNewProcess()
        assertNull(AaRestartGap.lastEndedAtMs)
        AaRestartGap.restore(prefs, nowElapsedMs = 650_000, nowWallMs = 1_700_000_650_000)
        assertEquals("ten minutes later by both clocks", 50_000L, AaRestartGap.lastEndedAtMs)
        assertTrue(AaWake.due(650_000, AaRestartGap.lastEndedAtMs))
    }

    @Test
    fun `a restored end the clocks disagree about is not believed`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.sessionEnded(prefs, nowElapsedMs = 50_000, nowWallMs = 1_700_000_050_000)
        // The phone restarted: the elapsed clock starts again, the wall clock goes on.
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.restore(prefs, nowElapsedMs = 20_000, nowWallMs = 1_700_000_650_000)
        assertNull(AaRestartGap.lastEndedAtMs)
        // The wall clock set back.
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.restore(prefs, nowElapsedMs = 650_000, nowWallMs = 1_600_000_000_000)
        assertNull(AaRestartGap.lastEndedAtMs)
    }

    @Test
    fun `the end in memory is not replaced by the file, and an answered handshake in a new process leaves nothing to restore`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.sessionEnded(prefs, nowElapsedMs = 50_000, nowWallMs = 1_700_000_050_000)
        AaRestartGap.testOnlyNewProcess()
        AaRestartGap.lastEndedAtMs = 99_000
        AaRestartGap.restore(prefs, nowElapsedMs = 650_000, nowWallMs = 1_700_000_650_000)
        assertEquals(99_000L, AaRestartGap.lastEndedAtMs)
    }

    @Test
    fun `reset forgets the end in memory and in the file`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        AaRestartGap.sessionEnded(prefs, nowElapsedMs = 50_000, nowWallMs = 1_700_000_050_000)
        AaRestartGap.reset(prefs)
        assertNull(AaRestartGap.lastEndedAtMs)
        assertTrue(prefs.map.isEmpty())
    }

    // ── what the receiver and the settings are wired to ────────────────────────────────────────────────────

    @Test
    fun `the switch is off by default and is the one the Android Auto configuration reads`() {
        assertFalse(REauSettings().experimentWakeAa)
        assertFalse(REauSettings().aaConfig.wakeBeforeDial)
        assertTrue(REauSettings(experimentWakeAa = true).aaConfig.wakeBeforeDial)
    }

    @Test
    fun `the switch is stored on its own, known by the start guard, said in the log and restarts nothing`() {
        val store = com.reau.app.settings.SettingsStore(KeystoreBackedPrefsTest.FakePrefs())
        assertFalse(store.load().experimentWakeAa)
        store.save(REauSettings(experimentWakeAa = true))
        assertTrue(store.load().experimentWakeAa)
        assertEquals(REauSettings(experimentWakeAa = true), store.load())
        val on = REauSettings(experimentWakeAa = true)
        assertEquals(listOf("experimentWakeAa"), com.reau.app.settings.ExperimentsGuard.experimentsOn(on))
        assertEquals(REauSettings(), com.reau.app.settings.ExperimentsGuard.allOff(on))
        assertTrue("experimentWakeAa=true" in com.reau.app.settings.SettingsInUse.line(on))
        assertTrue(com.reau.app.settings.SettingsInUse.experimentsLine(on)!!, "Android Auto woken" in com.reau.app.settings.SettingsInUse.experimentsLine(on)!!)
        assertEquals(listOf("experimentWakeAa: false -> true"), REauSettings().changesTo(on))
        assertEquals(emptySet<com.reau.app.service.LiveTuning.Step>(), com.reau.app.service.LiveTuning.steps(REauSettings(), on))
        assertTrue("sanitized keeps it", REauSettings(experimentWakeAa = true).sanitized(debugBuild = false).experimentWakeAa)
    }

    @Test
    fun `the row has its title and line in the three languages, and the line says Experimental`() {
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            assertTrue("$folder title", xml.contains("<string name=\"exp_wake_aa_title\">"))
            val sub = Regex("""<string name="exp_wake_aa_sub">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$folder line", sub != null && "Experimental" in sub && "700" in sub)
        }
        val screen = File("src/main/java/com/reau/app/ui/SettingsExperimental.kt").readText()
        assertTrue(screen.contains("s.experimentWakeAa, { change(s.copy(experimentWakeAa = it)) }, \"exp_wake_aa\""))
    }

    @Test
    fun `the receiver wakes through the gate right before each dial, behind the switch, and keeps the end of an answered session`() {
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        val dialing = receiver.indexOf("waking.dialing({ SystemClock.elapsedRealtime() }, { AaRestartGap.lastEndedAtMs }) { nextSocket(mine, tries = refused.count) }")
        assertTrue("the dial is made inside the gate", dialing > 0)
        assertTrue("the first wait after a stop comes before the gate", receiver.indexOf("waitAfterTheLastStop()\n") in 1 until dialing)
        assertTrue(receiver.contains("enabled = { config.wakeBeforeDial && callIn == null }"))
        assertTrue("a negotiation that got no answer is told to the gate", receiver.contains("if (result == Handshake.MUTE) waking.muteNegotiation()"))
        assertTrue("an answered session leaves its end", receiver.contains("if (answered) AaRestartGap.sessionEnded(context, SystemClock.elapsedRealtime(), System.currentTimeMillis())"))
        assertTrue(receiver.contains("return Handshake.failed(transport.versionAnswered)"))
        assertTrue("an answered session leaves its end, whichever way it ends", receiver.contains("            keepEnd(answered)\n"))
        assertNotNull(Regex("""val wake = options\.wake \?: ::openAndroidAutoSettings""").find(receiver))
    }

    @Test
    fun `the wake is the way into Android Auto's settings the app already has for a server that is off`() {
        val receiver = File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        assertTrue(receiver.contains("AndroidAuto.openSettings(context, say = ::logReceiver)"))
        val main = File("src/main/java/com/reau/app/MainActivity.kt").readText()
        assertTrue(main.contains("AndroidAutoStore.open(this, say = { RideDiagnostics.log(\"aa-server\", it) })"))
    }
}
