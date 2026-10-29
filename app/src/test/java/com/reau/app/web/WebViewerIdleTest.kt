package com.reau.app.web

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A browser that vanishes without closing the connection (the phone left the Wi-Fi) used to hold one of the 2 places until a video
 * write failed, which could take minutes: after the upgrade the server read with no time limit. Now it pings, and a viewer that says
 * nothing for [IDLE_MS] (the answers to the pings count) is let go. Real sockets on 127.0.0.1, with the times shortened.
 */
class WebViewerIdleTest {
    private val guard = WebGuard(draw = { 4821 })
    private val servers = mutableListOf<WebServer>()
    private val clients = mutableListOf<WsTestClient>()
    private val counts = CopyOnWriteArrayList<Int>()

    private val events = object : WebServer.Events {
        override fun page() = ByteArray(0)
        override fun onViewers(count: Int) { counts += count }
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.socket.close() } }
        servers.forEach { it.stop() }
    }

    private fun start(): WebServer {
        val port = ServerSocket(0).use { it.localPort }
        val settings = WebServer.Settings(guard, port..port, maxViewers = 1, allowed = { true }, thePhone = { false }, pingMs = PING_MS, idleMs = IDLE_MS)
        return WebServer(settings, events).also { servers += it; assertTrue(it.start() != null) }
    }

    private fun join(server: WebServer): WsTestClient {
        val client = WsTestClient(server.port).also { clients += it }
        assertTrue(client.upgrade(guard.pin).startsWith("HTTP/1.1 101"))
        await("the viewer is in") { server.viewerCount == 1 }
        return client
    }

    private fun await(what: String, ms: Long = 4_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }

    @Test
    fun `a viewer that goes mute is let go after the idle time and its place is free again`() {
        val server = start()
        join(server)                                    // never reads, never answers a ping
        await("the mute viewer is let go") { server.viewerCount == 0 }
        // the count goes to 0 a moment before the listener is told: wait for the telling, not for the count
        await("the listener is told 1 and then 0") { counts.toList() == listOf(1, 0) }
        assertEquals("told 1 and then 0", listOf(1, 0), counts.toList())
        join(server)                                    // maxViewers is 1: the place came back
    }

    @Test
    fun `a viewer that answers the pings stays well past the idle time`() {
        val server = start()
        val client = join(server)
        var pings = 0
        val until = System.currentTimeMillis() + IDLE_MS * 3
        while (System.currentTimeMillis() < until) {
            val (opcode, payload) = client.frame() ?: break
            if (opcode == WebSocketCodec.PING) {
                pings++
                client.sendFrame(WebSocketCodec.PONG, payload)
            }
        }
        assertTrue("it was pinged more than a few times: $pings", pings >= 5)
        assertEquals("and is still in", 1, server.viewerCount)
    }

    @Test
    fun `a viewer that sends messages but never answers a ping is alive too`() {
        val server = start()
        val client = join(server)
        val until = System.currentTimeMillis() + IDLE_MS * 3
        while (System.currentTimeMillis() < until) {
            client.sendText("touch")
            Thread.sleep(PING_MS)
        }
        assertEquals(1, server.viewerCount)
    }

    private companion object {
        const val PING_MS = 200L
        const val IDLE_MS = 1_000
    }
}
