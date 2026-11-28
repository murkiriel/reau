package com.reau.app.dash

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 35 S: [DashSocket] asks the one [SendPacer] before every packet it sends, the control channel's ([DashSocket.send], [DashSocket.sendBatch]) and the video's
 * ([DashSocket.sendRtp]) alike, so the 2 ms are between any two of them; and the rider's switch reaches it.
 */
class DashSocketPacingTest {
    private var socket: DashSocket? = null
    private var fakeDash: DatagramSocket? = null
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)
    private val ms = 1_000_000L
    private val clock = AtomicLong(0L)
    private val deadlines = mutableListOf<Long>()

    @After
    fun tearDown() {
        socket?.close()
        fakeDash?.close()
    }

    private fun open(enabled: Boolean = true): DashSocket {
        val pacer = SendPacer(enabled, nanoTime = { clock.get() }, waitUntil = { deadline ->
            deadlines += deadline
            clock.set(maxOf(clock.get(), deadline))
        })
        return DashSocket(ctrlDest = loopback, rtpDest = loopback, pacer = pacer).also { socket = it }
    }

    private val command = ByteArray(17)
    private val rtp = ByteArray(40)

    @Test
    fun `two commands in a row leave 2 ms apart`() {
        val s = open()
        s.send(command)
        s.send(command)
        assertEquals(listOf(2 * ms), deadlines)
    }

    @Test
    fun `two video packets in a row leave 2 ms apart`() {
        val s = open()
        s.sendRtp(rtp)
        s.sendRtp(rtp)
        assertEquals(listOf(2 * ms), deadlines)
    }

    @Test
    fun `the commands and the video are one timeline, in either order`() {
        val s = open()
        s.send(command)
        s.sendRtp(rtp)
        s.send(command)
        s.sendRtp(rtp)
        assertEquals(listOf(2 * ms, 4 * ms, 6 * ms), deadlines)
    }

    @Test
    fun `a batch of answers leaves its packets 2 ms apart`() {
        val s = open()
        s.sendBatch(listOf(command, command, command))
        assertEquals(listOf(2 * ms, 4 * ms), deadlines)
    }

    @Test
    fun `a packet 3 ms after the last one leaves at once`() {
        val s = open()
        s.send(command)
        clock.set(3 * ms)
        s.sendRtp(rtp)
        assertTrue(deadlines.isEmpty())
    }

    @Test
    fun `with the switch off nothing waits on either path`() {
        val s = open(enabled = false)
        repeat(3) {
            s.send(command)
            s.sendRtp(rtp)
        }
        s.sendBatch(listOf(command, command))
        assertTrue(deadlines.isEmpty())
    }

    @Test
    fun `the switch given to the socket is the pacer's, on unless it says off`() {
        assertTrue(DashSocket(ctrlDest = loopback, rtpDest = loopback).also { socket = it }.pacer.enabled)
        socket?.close()
        assertFalse(DashSocket(ctrlDest = loopback, rtpDest = loopback, pacePackets = false).also { socket = it }.pacer.enabled)
        socket?.close()
        assertTrue(DashSocket(ctrlDest = loopback, rtpDest = loopback, pacePackets = true).also { socket = it }.pacer.enabled)
    }

    /** A socket whose pacer and black box read the same test clock, so the black box's notes can be compared with the pacer's slots. */
    private fun openWithBlackBox(): Pair<DashSocket, BlackBox> {
        val box = BlackBox(clockNs = { clock.get() })
        return open().also { it.stats = DashStats(blackBox = box) } to box
    }

    @Test
    fun `the black box notes a video packet at the moment of its send, after the pacer's wait, and the wait is not the send call's duration`() {
        val (s, box) = openWithBlackBox()
        s.sendRtp(rtp)
        s.sendRtp(rtp)
        val snap = box.snapshot()
        assertEquals(2, snap.count)
        assertEquals("the first leaves at once", 0L, snap.atUs[0])
        assertEquals("the second is noted at its slot, 2 ms later", 2_000L, snap.atUs[1])
        assertEquals("the wait is not counted as the time the send call took", 0L, snap.sendUs(1))
    }

    @Test
    fun `the black box notes a command after the pacer's wait, with the commands and the video in one timeline`() {
        val (s, box) = openWithBlackBox()
        s.send(command)
        s.sendRtp(rtp)
        s.send(command)
        assertEquals(listOf(0L, 2_000L, 4_000L), box.snapshot().atUs.toList())
    }

    @Test
    fun `a packet that did not leave is not noted by the black box, the pacer's wait or not`() {
        val (s, box) = openWithBlackBox()
        s.close()
        s.send(command)
        s.sendRtp(rtp)
        assertEquals(0, box.snapshot().count)
    }

    private fun text(path: String) = File("src/main/java/com/reau/app/$path").readText()

    /** What reached [dash] so far (read until it is quiet for a moment); a packet that has not left the phone is not in it. */
    private fun heard(dash: DatagramSocket): Int {
        var n = 0
        val buffer = ByteArray(2_048)
        dash.soTimeout = HEARD_QUIET_MS
        while (true) {
            try {
                dash.receive(DatagramPacket(buffer, buffer.size))
                n++
            } catch (_: SocketTimeoutException) {
                return n
            }
        }
    }

    /**
     * Two sends through [send] to a real loopback "dash", the pacer's wait being a hook of the test (26/11/2026, the source test of the order is gone): in the wait of
     * the second one, the first packet has reached the dash and the second has not, and another thread can send a packet at once (so the waiting one holds no lock).
     * Returns what the dash had heard in the wait, and whether the other thread finished while the first was still waiting.
     */
    private fun waitOfTheSecond(send: (DashSocket) -> Unit): Pair<Int, Boolean> {
        val dash = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { fakeDash = it }
        val dashAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), dash.localPort)
        var heardInTheWait = -1
        var otherFinished = false
        var inTheWait = false
        lateinit var s: DashSocket
        val pacer = SendPacer(true, nanoTime = { clock.get() }, waitUntil = { deadline ->
            clock.set(maxOf(clock.get(), deadline))
            // The other thread's own wait comes back here: it is not looked at again.
            if (!inTheWait) {
                inTheWait = true
                heardInTheWait = heard(dash)
                val other = thread { send(s) }
                other.join(OTHER_THREAD_MS)
                otherFinished = !other.isAlive
            }
        })
        s = DashSocket(ctrlDest = dashAddress, rtpDest = dashAddress, pacer = pacer).also { socket = it }
        send(s)
        send(s)
        return heardInTheWait to otherFinished
    }

    @Test
    fun `a command waits for its slot before it leaves and holds nothing while it waits`() {
        val (heardInTheWait, otherFinished) = waitOfTheSecond { it.send(command) }
        assertEquals("only the first command had left when the second waited", 1, heardInTheWait)
        assertTrue("a send from another thread goes through while the second waits (no lock held)", otherFinished)
    }

    @Test
    fun `a video packet waits for its slot before it leaves and holds nothing while it waits`() {
        val (heardInTheWait, otherFinished) = waitOfTheSecond { it.sendRtp(rtp) }
        assertEquals("only the first packet had left when the second waited", 1, heardInTheWait)
        assertTrue("a send from another thread goes through while the second waits (no lock held)", otherFinished)
    }

    @Test
    fun `the setting reaches the socket each time the session opens its sockets`() {
        val live = text("service/LiveSides.kt")
        assertTrue(live, "val pace = { runCatching { SettingsStore.open(context).load().pacePackets }.getOrDefault(true) }" in live)
        assertTrue(live, "dashSocket = { DashSocket(it, pacePackets = pace()) }," in live)
    }

    @Test
    fun `the session hands the pacer to the statistics, and the session's first line says if it is on`() {
        assertTrue("stats.pacer = sock.pacer" in text("dash/DashSession.kt"))
        val socket = text("dash/DashSocket.kt")
        assertTrue(socket, "com.reau.app.data.RideDiagnostics.log(\"dash\", pacingLine())" in socket)
        assertTrue(socket, "\"packet pacing on: at least" in socket && "\"packet pacing off:" in socket)
    }

    private companion object {
        /** How long the fake dash waits for one more packet before it says it has heard them all (loopback delivers inside the send itself). */
        const val HEARD_QUIET_MS = 100

        /** What the other thread's send is given to finish while the first one waits. */
        const val OTHER_THREAD_MS = 3_000L
    }
}
