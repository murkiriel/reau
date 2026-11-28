package com.reau.app.dash

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real [DashSession] against [FakeDashPeer] when Android refuses to bind the socket to the dash's network (EPERM): the one try through the phone's own
 * address, the proof of the route, and the refusal standing as always when the try proves nothing (25/11/2026). The facts of the network are given by the test.
 */
class DashSessionLocalAddressTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: DashSession? = null
    private var fakeDash: FakeDashPeer? = null
    private var mute: DatagramSocket? = null
    private val errors = CopyOnWriteArrayList<String>()
    private val bindResults = CopyOnWriteArrayList<Boolean>()
    private val normalCalls = AtomicInteger()
    private val localCalls = AtomicInteger()
    private val localAddresses = CopyOnWriteArrayList<InetAddress>()
    private val logFile = java.nio.file.Files.createTempFile("local-address", ".log").toFile()
    private val loopback = InetAddress.getLoopbackAddress()
    private val eperm = IOException("Binding socket to network 454 failed: EPERM")

    @After
    fun tearDown() {
        session?.disconnect()
        scope.cancel()
        fakeDash?.close()
        mute?.close()
        com.reau.app.data.RideDiagnostics.testOnlyReset()
        logFile.delete()
    }

    private fun await(what: String, timeoutMs: Long = 8_000, until: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (!until()) {
            assertTrue("timed out waiting for $what (state ${session?.state?.value}, errors $errors)", System.nanoTime() < end)
            Thread.sleep(10)
        }
    }

    private fun logged(): List<String> {
        com.reau.app.data.RideDiagnostics.logAndWait("end", "marker", timeoutMs = 10_000)
        return logFile.readLines()
    }

    /**
     * A session whose bind is refused when [bindRefused], with the network's [facts], and whose local-address sockets point at the fake dash, or at a dash that
     * says nothing when [silent], or cannot be opened when [cannotOpen].
     */
    private fun start(
        facts: LocalAddressRetry.Facts,
        bindRefused: Boolean = true,
        silent: Boolean = false,
        cannotOpen: Boolean = false,
        talksWithNoKey: Boolean = false,
        youngAt: Int? = null,
        neverGivesKey: Boolean = false,
        proofMs: Long = 400,
    ): DashSession {
        com.reau.app.data.RideDiagnostics.testOnlySetFile(logFile)
        val fake = FakeDashPeer(clock = { System.nanoTime() / 1_000_000L }).also { fakeDash = it }
        // A dash that is ready (its counter is past 26) sends its heartbeat by itself and gives no key to our greeting: packets come in, the answer does not.
        if (talksWithNoKey) {
            fake.beYoung(30)
            fake.ignoreFirstGreetings = Int.MAX_VALUE
        }
        // A dash on for a few seconds (the Android that joins it by itself right after the ignition): its counter goes up to 26 and then it gives the key, or never does.
        if (youngAt != null) fake.beYoung(youngAt)
        if (neverGivesKey) fake.ignoreFirstGreetings = Int.MAX_VALUE
        val quiet = DatagramSocket(0, loopback).also { mute = it }
        val ctrl = if (silent) InetSocketAddress(loopback, quiet.localPort) else fake.ctrlDest
        val rtp = if (silent) InetSocketAddress(loopback, quiet.localPort) else fake.rtpDest
        val s = DashSession(
            scope,
            socketFactory = {
                normalCalls.incrementAndGet()
                if (bindRefused) throw eperm else DashSocket(null, ctrlDest = fake.ctrlDest, rtpDest = fake.rtpDest)
            },
            // With a dash that talks, the proof waits long enough for its heartbeat (once a second) to come in, to show that it is not the proof.
            timing = DashSession.Timing(localProofMs = if (talksWithNoKey) 2_500 else proofMs),
            networkFacts = { facts },
            localSocketFactory = { ip ->
                localCalls.incrementAndGet()
                localAddresses += ip
                if (cannotOpen) throw java.net.SocketException("Cannot assign requested address")
                DashSocket(null, ctrlDest = ctrl, rtpDest = rtp, localAddress = ip)
            },
        ).also { session = it }
        s.onError = { errors += it }
        s.onBindResult = { _, refused -> bindResults += refused }
        s.connect("DashFake")
        return s
    }

    private val live = LocalAddressRetry.Facts(alive = true, ipv4 = InetAddress.getLoopbackAddress())

    @Test
    fun `an EPERM on a live network goes on through the local address once the dash answers, and the bind is told as one that worked`() {
        val s = start(live)
        await("the session to be ready") { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(errors.toString(), DashState.READY, s.state.value)
        assertEquals("the normal bind was tried first, once", 1, normalCalls.get())
        assertEquals(1, localCalls.get())
        assertEquals(loopback, localAddresses.single())
        assertEquals("the refusal was gone around and the route proved: told once as a bind that worked, which closes the count of refusals", listOf(false), bindResults.toList())
        val lines = logged()
        assertTrue(lines.toString(), lines.any { it.endsWith("[session] W: " + LocalAddressRetry.TRYING_LINE) })
        assertTrue(lines.toString(), lines.any { LocalAddressRetry.WORKED_LINE in it })
        assertFalse("no address in the log lines of the try", lines.filter { "local address" in it }.any { "127.0.0.1" in it })
    }

    @Test
    fun `the count of refusals is closed only by a route that was proved, never at the opening of the sockets`() {
        val s = start(live, silent = true)
        await("the failure") { s.state.value == DashState.ERROR }
        assertFalse("no bind that worked told for a try that proved nothing", false in bindResults.toList())
        assertEquals(listOf(true), bindResults.toList())
    }

    @Test
    fun `a dash that sends its telemetry but no key proves nothing, and the refusal stands`() {
        val s = start(live, talksWithNoKey = true)
        await("the failure") { s.state.value == DashState.ERROR }
        assertTrue("its heartbeats did arrive, after the sockets were open (the proof is not that)", fakeDash!!.tlvsSent.get() >= 2)
        assertEquals(listOf(true), bindResults.toList())
        assertEquals(listOf(NetworkBlock.describe(eperm)), errors.toList())
        val lines = logged()
        assertTrue(lines.toString(), lines.any { "the dash did not answer through the local address in " in it })
        assertFalse(lines.toString(), lines.any { LocalAddressRetry.WORKED_LINE in it })
    }

    @Test
    fun `a young dash with a good route gets its time and the proof counts from its being ready, not from the greeting`() {
        // Ready 4 s after it turned on, with a proof of 2 s: counted from the greeting the refusal would stand at 2 s, long before the key.
        val s = start(live, youngAt = 22, proofMs = 2_000)
        await("the session to be ready", timeoutMs = 20_000) { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(errors.toString(), DashState.READY, s.state.value)
        assertEquals("told as a bind that worked only when the route was proved, once", listOf(false), bindResults.toList())
        assertTrue(logged().any { LocalAddressRetry.WORKED_LINE in it })
    }

    @Test
    fun `a young dash that never answers the greeting leaves the refusal standing once it is ready and the time of the proof is up`() {
        val began = System.nanoTime()
        val s = start(live, youngAt = 22, neverGivesKey = true, proofMs = 1_500)
        await("the failure", timeoutMs = 20_000) { s.state.value == DashState.ERROR }
        val tookMs = (System.nanoTime() - began) / 1_000_000L
        assertTrue("not before it was ready (about 4 s): $tookMs ms", tookMs >= 3_500)
        assertEquals(listOf(true), bindResults.toList())
        assertEquals(listOf(NetworkBlock.describe(eperm)), errors.toList())
        assertTrue(logged().any { "the dash did not answer through the local address in " in it })
    }

    @Test
    fun `a dash that was already ready and does not answer ends the try 5 s after the greeting, as before this fix`() {
        val began = System.nanoTime()
        val s = start(live, youngAt = 40, neverGivesKey = true, proofMs = 800)
        await("the failure", timeoutMs = 20_000) { s.state.value == DashState.ERROR }
        val tookMs = (System.nanoTime() - began) / 1_000_000L
        assertTrue("no wait for readiness: $tookMs ms", tookMs < 3_000)
        assertEquals(listOf(true), bindResults.toList())
    }

    @Test
    fun `a dash that does not answer through the local address leaves the refusal standing, as before`() {
        val s = start(live, silent = true)
        await("the failure") { s.state.value == DashState.ERROR }
        assertEquals("the refusal is told, so the network is dropped", listOf(true), bindResults.toList())
        assertEquals("the failure is the EPERM's own, as it always was", listOf(NetworkBlock.describe(eperm)), errors.toList())
        assertEquals(1, localCalls.get())
        val lines = logged()
        assertTrue(lines.toString(), lines.any { "the dash did not answer through the local address in " in it })
        assertFalse(lines.toString(), lines.any { LocalAddressRetry.WORKED_LINE in it })
    }

    @Test
    fun `a dead network is not tried and the refusal ends the session as before`() {
        val s = start(LocalAddressRetry.Facts(alive = false, ipv4 = loopback))
        await("the failure") { s.state.value == DashState.ERROR }
        assertEquals(0, localCalls.get())
        assertEquals(listOf(true), bindResults.toList())
        assertEquals(listOf(NetworkBlock.describe(eperm)), errors.toList())
        assertFalse(logged().any { LocalAddressRetry.TRYING_LINE in it })
    }

    @Test
    fun `a network with no IPv4 address is not tried`() {
        val s = start(LocalAddressRetry.Facts(alive = true, ipv4 = null))
        await("the failure") { s.state.value == DashState.ERROR }
        assertEquals(0, localCalls.get())
        assertEquals(listOf(true), bindResults.toList())
    }

    @Test
    fun `sockets that cannot be opened on the local address leave the refusal standing at once`() {
        val s = start(live, cannotOpen = true)
        await("the failure") { s.state.value == DashState.ERROR }
        assertEquals(1, localCalls.get())
        assertEquals(listOf(true), bindResults.toList())
        assertEquals(listOf(NetworkBlock.describe(eperm)), errors.toList())
        assertTrue(logged().any { "the sockets could not be opened on the local address" in it })
    }

    @Test
    fun `a bind that works never looks at the local address, and tells it worked`() {
        val s = start(live, bindRefused = false)
        await("the session to be ready") { s.state.value == DashState.READY || s.state.value == DashState.ERROR }
        assertEquals(errors.toString(), DashState.READY, s.state.value)
        assertEquals(0, localCalls.get())
        assertEquals(listOf(false), bindResults.toList())
        assertFalse(logged().any { "local address" in it })
    }

    @Test
    fun `without facts the session never tries, as every session did before`() {
        val s = start(LocalAddressRetry.NONE)
        await("the failure") { s.state.value == DashState.ERROR }
        assertEquals(0, localCalls.get())
        assertEquals(listOf(true), bindResults.toList())
    }
}
