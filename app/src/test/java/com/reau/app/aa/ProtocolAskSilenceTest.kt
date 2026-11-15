package com.reau.app.aa

import com.reau.app.data.KeystoreBackedPrefsTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Silence is not a refusal. With its battery restricted, Android freezes Android Auto's projection process for minutes after a session, and a
 * dial in that time gets no answer at all. 6.0 had worked three times on 14/11/2026 when one such dial made the app ask 1.7 until it closed. So a
 * handshake that asked 6.0 and got no answer counts as a failure of 6.0 only when 6.0 has never completed with the Android Auto installed now; a
 * refusal, or a failure after the answer, always does.
 */
class ProtocolAskSilenceTest {
    /** The proof in memory, with the `versionCode` of the Android Auto installed now in [installedNow]. */
    private class FakeProof(var kept: Long? = null, var installedNow: Long? = 100L) : ProtocolAsk.Proof {
        override fun provenOn() = kept
        override fun prove(versionCode: Long?) {
            kept = versionCode
        }
        override fun installed() = installedNow
    }

    private val said = mutableListOf<Pair<String, Boolean>>()
    private val say: (String, Boolean) -> Unit = { line, failed -> said += line to failed }
    private val proof = FakeProof()

    @Before
    fun bind() {
        ProtocolAsk.testOnlyReset()
        ProtocolAsk.use(proof)
    }

    @After
    fun unbind() = ProtocolAsk.testOnlyReset()

    private val sixIsAsked get() = ProtocolAsk.resolve(wantSix = true, minor = 7) == ProtocolAsk.SIX

    @Test
    fun `silence with 6 dot 0 proven on this Android Auto keeps asking 6 dot 0, and the log says why`() {
        proof.kept = 100L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertTrue("6.0 is asked again", sixIsAsked)
        assertEquals(1, said.size)
        assertFalse("it is no failure", said[0].second)
        assertTrue(said[0].first, "silence is not a refusal" in said[0].first && "completed before with this Android Auto" in said[0].first)
        assertTrue(said[0].first, "version code 100" in said[0].first && "6.0 is asked again" in said[0].first)
        assertEquals("the proof stays", 100L, proof.kept)
    }

    @Test
    fun `silence with no proof asks 1 dot 7 from now on`() {
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertFalse(sixIsAsked)
        assertEquals(ProtocolAsk.Version(1, 7), ProtocolAsk.resolve(true, 7))
        assertTrue(said[0].second)
        assertTrue(said[0].first, "6.0 never completed with this Android Auto" in said[0].first && "1.7 is asked from now on" in said[0].first)
    }

    @Test
    fun `a refusal asks 1 dot 7 even with the proof`() {
        proof.kept = 100L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = "1.7, status -1", say = say)
        assertFalse(sixIsAsked)
        assertTrue(said[0].second)
        assertEquals("a refusal does not take the proof away", 100L, proof.kept)
    }

    @Test
    fun `a failure after the answer, the TLS step, asks 1 dot 7 even with the proof`() {
        proof.kept = 100L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = "6.0, status 0", say = say)
        assertFalse(sixIsAsked)
        assertTrue(said[0].second)
    }

    @Test
    fun `an updated Android Auto starts the proof again`() {
        proof.kept = 100L
        proof.installedNow = 101L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertFalse("silence on a version 6.0 never completed with", sixIsAsked)
        assertNull("the proof of the old version is gone", proof.kept)
        assertTrue(said[0].second)
    }

    @Test
    fun `a handshake that completes is the proof, for the installed version`() {
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = true, answered = "1.7, status 0", say = say)
        assertEquals(100L, proof.kept)
        assertTrue(sixIsAsked)
        // ...and then the silence is only silence.
        said.clear()
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertTrue(sixIsAsked)
        assertFalse(said[0].second)
    }

    @Test
    fun `a handshake that completes on an updated Android Auto replaces the old proof`() {
        proof.kept = 100L
        proof.installedNow = 101L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = true, answered = "1.7, status 0", say = say)
        assertEquals(101L, proof.kept)
    }

    @Test
    fun `with the installed version not known there is no proof to make or to use`() {
        proof.installedNow = null
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = true, answered = "1.7, status 0", say = say)
        assertNull(proof.kept)
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertFalse(sixIsAsked)
    }

    @Test
    fun `a proof of another version than the installed one is dropped even when the handshake did not complete after an answer`() {
        proof.kept = 100L
        proof.installedNow = 102L
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = "1.7, status -1", say = say)
        assertNull(proof.kept)
    }

    @Test
    fun `a handshake that asked 1 dot 7 neither proves nor spends anything`() {
        proof.kept = 100L
        ProtocolAsk.settle(ProtocolAsk.Version(1, 7), ok = false, answered = null, say = say)
        ProtocolAsk.settle(ProtocolAsk.Version(1, 7), ok = true, answered = "1.7, status 0", say = say)
        assertEquals(100L, proof.kept)
        assertEquals(emptyList<Pair<String, Boolean>>(), said)
    }

    // ── kept in the app's file ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the proof is kept in the file and read back by a later process`() {
        val prefs = KeystoreBackedPrefsTest.FakePrefs()
        ProtocolAsk.use(ProtocolAsk.PrefsProof(prefs) { 29_912_077L })
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = true, answered = "1.7, status 0", say = say)
        assertEquals(29_912_077L, prefs.getLong("six_completed_version_code", 0L))
        // A new process: nothing in memory, the same file.
        ProtocolAsk.testOnlyReset()
        ProtocolAsk.use(ProtocolAsk.PrefsProof(prefs) { 29_912_077L })
        said.clear()
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertTrue(sixIsAsked)
        assertFalse(said[0].second)
        // The same file with an updated Android Auto: no proof, and the file forgets it.
        ProtocolAsk.testOnlyReset()
        ProtocolAsk.use(ProtocolAsk.PrefsProof(prefs) { 29_912_078L })
        ProtocolAsk.settle(ProtocolAsk.SIX, ok = false, answered = null, say = say)
        assertFalse(sixIsAsked)
        assertFalse(prefs.contains("six_completed_version_code"))
    }

    @Test
    fun `the receiver binds the proof before it asks, only with the switch on, and the switch is still the one that turns it all on`() {
        val receiver = java.io.File("src/main/java/com/reau/app/aa/AaReceiver.kt").readText()
        val bind = receiver.indexOf("if (config.protocolSix) ProtocolAsk.use(ProtocolAsk.proofIn(context))")
        assertTrue(bind > 0)
        assertTrue(receiver.indexOf("ProtocolAsk.resolve(config.protocolSix, config.protocolMinor)") > bind)
        val ask = java.io.File("src/main/java/com/reau/app/aa/ProtocolAsk.kt").readText()
        assertTrue("the versionCode of the installed Android Auto", ask.contains("PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(AndroidAuto.PACKAGE, 0))"))
    }
}
