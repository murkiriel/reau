package com.reau.app.dash

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
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
    private val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), 9)
    private val ms = 1_000_000L
    private val clock = AtomicLong(0L)
    private val deadlines = mutableListOf<Long>()

    @After
    fun tearDown() {
        socket?.close()
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

    private fun text(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the two send paths ask the pacer right before the socket send, outside every lock`() {
        val source = text("dash/DashSocket.kt")
        val wire = source.substringAfter("private fun wire(").substringBefore("private fun heldBack")
        assertTrue(wire, wire.indexOf("pacer.pace()") in 0 until wire.indexOf("txSocket.send("))
        val rtp = source.substringAfter("fun sendRtp(").substringBefore("private fun rtpSendFailed")
        assertTrue(rtp, rtp.indexOf("pacer.pace()") in 0 until rtp.indexOf("rtpSocket.send("))
        assertEquals("one ask in each path, none anywhere else", 2, Regex("""pacer\.pace\(\)""").findAll(source).count())
        assertFalse("never inside the control channel's lock", wire.contains("synchronized"))
    }

    @Test
    fun `the setting reaches the socket each time the session opens its sockets, for both the network's and the local address's`() {
        val live = text("service/LiveSides.kt")
        assertTrue(live, "val pace = { runCatching { SettingsStore.open(context).load().pacePackets }.getOrDefault(true) }" in live)
        assertTrue(live, "dashSocket = { DashSocket(it, pacePackets = pace()) }," in live)
        assertTrue(live, "localSocket = { DashSocket(null, localAddress = it, pacePackets = pace()) }," in live)
        assertTrue(live, "socketFactory = setup.dashSocket, localSocketFactory = setup.localSocket," in live)
    }

    @Test
    fun `the session hands the pacer to the statistics, and the session's first line says if it is on`() {
        assertTrue("stats.pacer = sock.pacer" in text("dash/DashSession.kt"))
        val socket = text("dash/DashSocket.kt")
        assertTrue(socket, "com.reau.app.data.RideDiagnostics.log(\"dash\", pacingLine())" in socket)
        assertTrue(socket, "\"packet pacing on: at least" in socket && "\"packet pacing off:" in socket)
    }
}
