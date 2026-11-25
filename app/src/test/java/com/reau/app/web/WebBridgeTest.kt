package com.reau.app.web

import com.reau.app.aa.AaAudioFeed
import com.reau.app.aa.PhoneTouch
import com.reau.app.settings.WebAudioMode
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web server tied to the projection: the switches, Android Auto's state, the touch, the video's life and the way
 * out.
 */
class WebBridgeTest {
    private class FakeSource : WebVideoSource {
        var starts = 0
        var stops = 0
        override fun start(): Boolean { starts++; return true }
        override fun stop() { stops++ }
        override fun requestKeyFrame() = Unit
    }

    private class FakeDelayer : Delayer {
        private class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        private val jobs = mutableListOf<Job>()
        override fun after(ms: Long, task: () -> Unit): () -> Unit {
            val job = Job(now + ms, task)
            jobs += job
            return { job.cancelled = true }
        }
        /**
         * Moves the clock by [ms], running each job in the order of its time, the ones that jobs ask for while running
         * included.
         */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                now = next.at
                next.cancelled = true
                next.task()
            }
            now = target
        }
    }

    private val source = FakeSource()
    private val delayer = FakeDelayer()
    private val touches = CopyOnWriteArrayList<PhoneTouch.Sent>()
    private val logs = CopyOnWriteArrayList<String>()
    private val page = "<html>the page</html>".toByteArray()
    private var pushOutput: (ByteArray, Boolean, Boolean) -> Unit = { _, _, _ -> }
    private val clients = mutableListOf<WsTestClient>()

    /** A free port and the few after it: the bridge listens on the first one still free, so a process that takes the port in between does no harm. */
    private fun freePorts() = ServerSocket(0).use { it.localPort }.let { it..minOf(it + 9, 65_535) }

    private val draws = ArrayDeque(listOf(4821, 1234, 5678, 9012))

    /** Every bridge a test made, released in [tearDown] so none outlives its test (nothing else holds on to them). */
    private val bridges = mutableListOf<WebBridge>()
    private val loopbackViewers = mutableListOf<WebViewer>()

    /** The generation the bridge had at the moment the server said it listens: a browser can come in from that moment on. */
    private val liveWhenListening = CopyOnWriteArrayList<Long>()

    private fun bridge(ports: IntRange = freePorts(), limits: WebGuard.Limits = WebGuard.Limits()) = WebBridge(
        WebBridge.Env(
            page = { page },
            video = { out -> pushOutput = out; source },
            sendTouch = { touches += it },
            delayer = delayer,
            log = { line ->
                logs += line
                if (line.startsWith("web: server on port")) liveWhenListening += bridges.last().liveGeneration
            },
            network = WebBridge.Network(ports, allowed = { true }, limits = limits, draw = { draws.removeFirst() }, thePhone = { false }),
        ),
    ).also { bridges += it }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.socket.close() } }
        loopbackViewers.forEach { runCatching { it.close() } }
        bridges.forEach { runCatching { it.release() } }
        // The count of the viewers goes to the events on a thread of its own: it must have ended before the next test, or it could
        // write on what that test's screen shows.
        eventually("every telling of the viewers' count ended") { WebViewers.tellingsInFlight.get() == 0 }
        WebAccess.clear()
    }

    /** Waits for [condition], however loaded the machine is; the limit is only there so a real hang fails instead of freezing. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            assertTrue("not in 60 s: $what", System.nanoTime() < until)
            Thread.sleep(10)
        }
    }

    /**
     * Returns once the server has handled everything [c] sent before this call: the server reads a browser's frames in order, so
     * the answer to a ping is behind them all. The texts it sent meanwhile are read and left.
     */
    private fun handled(c: WsTestClient) {
        c.sendFrame(WebSocketCodec.PING, byteArrayOf(1))
        while (true) {
            val frame = checkNotNull(c.frame()) { "the connection ended before the ping was answered" }
            if (frame.first == WebSocketCodec.PONG) return
        }
    }

    private fun join(access: WebAccess.Access): WsTestClient {
        val c = WsTestClient(access.port).also { clients += it }
        assertTrue(c.upgrade(access.pin).startsWith("HTTP/1.1 101"))
        return c
    }

    private fun running(b: WebBridge, touch: Boolean = false): WebAccess.Access {
        b.apply(webView = true, webTouch = touch)
        val access = WebAccess.state.value
        assertNotNull(access)
        return access!!
    }

    @Test
    fun `with the switch off nothing listens and nothing is told`() {
        val b = bridge()
        b.apply(webView = false, webTouch = true)
        assertNull(WebAccess.state.value)
        assertEquals(0, source.starts)
    }

    @Test
    fun `with the switch on the server listens and says where, with a pin of four digits`() {
        val b = bridge()
        val access = running(b)
        assertEquals("4821", access.pin)
        val page = WsTestClient(access.port).also { clients += it }
        page.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(page.readHead().startsWith("HTTP/1.1 200"))
        val yes = WsTestClient(access.port).also { clients += it }
        yes.send("GET /auth?k=4821 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(yes.readHead().startsWith("HTTP/1.1 204"))
        val no = WsTestClient(access.port).also { clients += it }
        no.send("GET /auth?k=0000 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(no.readHead().startsWith("HTTP/1.1 403"))
        b.release()
    }

    @Test
    fun `the pin is new each time the server comes up, and never in the log`() {
        val b = bridge()
        val first = running(b)
        b.apply(webView = false, webTouch = false)
        assertNull(WebAccess.state.value)
        val second = running(b)
        assertEquals("4821", first.pin)
        assertEquals("1234", second.pin)
        val log = logs.joinToString("\n")
        // The port is a random number and may contain the four digits of the PIN ("port 34821" holds "4821": it failed one run
        // in a few thousand), so it is left out of the look for the PIN.
        val withoutPort = log.replace(Regex("""port \d+"""), "port")
        assertFalse(log, withoutPort.contains(first.pin) || withoutPort.contains(second.pin))
        assertTrue(log, log.contains("web: server on port"))
        b.release()
    }

    @Test
    fun `too many wrong pins change the pin and the screen is told`() {
        val b = bridge(limits = WebGuard.Limits(maxPerAddress = 9, rotateAfter = 2))
        val access = running(b)
        assertEquals("4821", access.pin)
        for (wrong in listOf("0000", "0001")) {
            val c = WsTestClient(access.port).also { clients += it }
            c.send("GET /auth?k=$wrong HTTP/1.1\r\nHost: x\r\n\r\n")
            c.readHead()
        }
        eventually("the screen has the new pin") { WebAccess.state.value?.pin == "1234" }
        assertEquals(access.port, WebAccess.state.value!!.port)
        val log = logs.joinToString("\n")
        assertTrue(log, log.contains("the pin changed"))
        assertFalse(log, log.contains("1234") || log.contains("4821"))
        b.release()
    }

    @Test
    fun `switching on twice keeps the same server`() {
        val b = bridge()
        val first = running(b)
        b.apply(webView = true, webTouch = true)
        assertEquals(first.port, WebAccess.state.value!!.port)
        assertEquals(first.pin, WebAccess.state.value!!.pin)
        b.release()
    }

    @Test
    fun `no free port is said and nothing is offered`() {
        val busy = ServerSocket(0)
        busy.use {
            val b = bridge(ports = it.localPort..it.localPort)
            b.apply(webView = true, webTouch = false)
            assertNull(WebAccess.state.value)
            assertTrue(logs.toString(), logs.any { l -> l.contains("no free port") })
        }
    }

    @Test
    fun `a viewer is first told the state, and the video starts with it`() {
        val b = bridge()
        val access = running(b)
        val c = join(access)
        assertEquals("{\"state\":\"off\",\"touch\":false}", c.text())
        eventually("video started") { source.starts == 1 }
        eventually("one viewer counted") { WebAccess.state.value?.viewers == 1 }
        b.release()
    }

    @Test
    fun `when Android Auto comes and goes the viewers are told`() {
        val b = bridge()
        val c = join(running(b))
        c.text()
        b.androidAutoChanged(active = true)
        assertEquals("{\"state\":\"live\",\"touch\":false}", c.text())
        b.androidAutoChanged(active = false)
        assertEquals("{\"state\":\"off\",\"touch\":false}", c.text())
        b.release()
    }

    @Test
    fun `a viewer who joins while Android Auto is on is told so at once`() {
        val b = bridge()
        running(b, touch = true)
        b.androidAutoChanged(active = true)
        val c = join(WebAccess.state.value!!)
        assertEquals("{\"state\":\"live\",\"touch\":true}", c.text())
        b.release()
    }

    @Test
    fun `switching the touch changes what viewers are told`() {
        val b = bridge()
        val c = join(running(b))
        c.text()
        b.apply(webView = true, webTouch = true)
        assertEquals("{\"state\":\"off\",\"touch\":true}", c.text())
        b.release()
    }

    @Test
    fun `touch reaches Android Auto only with the touch switch on, as one stroke at a time`() {
        val b = bridge()
        val c = join(running(b, touch = false))
        c.text()
        c.sendText("{\"a\":\"d\",\"x\":10,\"y\":20}")
        handled(c)
        assertTrue("the touch switch is off: nothing goes", touches.isEmpty())

        b.apply(webView = true, webTouch = true)
        c.text()
        c.sendText("{\"a\":\"m\",\"x\":1,\"y\":1}") // a move with no touch down is dropped
        c.sendText("{\"a\":\"d\",\"x\":10,\"y\":20}")
        c.sendText("{\"a\":\"m\",\"x\":11,\"y\":21}")
        c.sendText("{\"a\":\"u\",\"x\":12,\"y\":22}")
        c.sendText("not a touch")
        c.sendText("{\"a\":\"d\",\"x\":9999,\"y\":1}")
        handled(c)
        assertEquals(
            listOf(
                PhoneTouch.Sent(PhoneTouch.Action.DOWN, 10, 20),
                PhoneTouch.Sent(PhoneTouch.Action.MOVE, 11, 21),
                PhoneTouch.Sent(PhoneTouch.Action.UP, 12, 22),
            ),
            touches.toList(),
        )
        b.release()
    }

    @Test
    fun `a stroke left open when the browser goes, or the touch is turned off, or the server stops, is cancelled for Android Auto`() {
        val b = bridge()
        val c = join(running(b, touch = true))
        c.text()
        c.sendText("{\"a\":\"d\",\"x\":10,\"y\":20}")
        c.sendText("{\"a\":\"m\",\"x\":30,\"y\":40}")
        eventually("down and move") { touches.size == 2 }
        c.socket.close()
        eventually("the browser went: the stroke is cancelled where it was") { touches.size == 3 }
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 30, 40), touches[2])

        val d = join(WebAccess.state.value!!)
        d.text()
        d.sendText("{\"a\":\"d\",\"x\":1,\"y\":2}")
        eventually("a new stroke") { touches.size == 4 }
        b.apply(webView = true, webTouch = false)
        assertEquals("the touch switch off cancels it", PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 1, 2), touches[4])

        b.apply(webView = true, webTouch = true)
        d.sendText("{\"a\":\"d\",\"x\":5,\"y\":6}")
        eventually("another") { touches.size == 6 }
        b.release()
        assertEquals("stopping cancels it", PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 5, 6), touches[6])
        assertEquals(7, touches.size)
    }

    @Test
    fun `Derrubar todos puts out every browser and changes the pin, so the ones put out must type the new one`() {
        val b = bridge()
        val access = running(b)
        val c = join(access)
        c.text()
        eventually("one viewer") { WebAccess.state.value?.viewers == 1 }
        WebAccess.kickAll()
        eventually("the pin changed on the screen") { WebAccess.state.value?.pin == "1234" }
        val old = WsTestClient(access.port).also { clients += it }
        old.send("GET /auth?k=4821 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue("the old pin is wrong now", old.readHead().startsWith("HTTP/1.1 403"))
        val fresh = WsTestClient(access.port).also { clients += it }
        fresh.send("GET /auth?k=1234 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue("the new one is right, with no hold", fresh.readHead().startsWith("HTTP/1.1 204"))
        eventually("nobody is in") { WebAccess.state.value?.viewers == 0 }
        b.release()
    }

    @Test
    fun `a slow viewer that overflows while others come and go, and the server stopping, never lock each other up`() {
        val b = bridge()
        val access = running(b)
        val slow = join(access) // never reads
        slow.text()
        eventually("video started") { source.starts == 1 }
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        val big = ByteArray(100_000)
        val pump = Thread {
            while (!stop.get()) {
                pushOutput(big, false, false)
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        repeat(12) {
            val c = WsTestClient(access.port).also { clients += it }
            if (c.upgrade(access.pin).startsWith("HTTP/1.1 101")) c.socket.close()
            Thread.sleep(40)
        }
        val releaser = Thread { b.release() }.apply { isDaemon = true; start() }
        releaser.join(30_000)
        assertFalse("release came back (no deadlock)", releaser.isAlive)
        stop.set(true)
        pump.join(30_000)
        assertFalse("the encoder's thread came back too", pump.isAlive)
    }

    @Test
    fun `what the encoder gives goes to every viewer, and a viewer who comes late gets the cache first`() {
        val b = bridge()
        val access = running(b)
        val early = join(access)
        early.text()
        eventually("video started") { source.starts == 1 }
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 1)
        val key = byteArrayOf(0, 0, 0, 1, 0x65, 2)
        val slice = byteArrayOf(0, 0, 0, 1, 0x41, 3)
        pushOutput(config, false, true)
        pushOutput(key, true, false)
        pushOutput(slice, false, false)
        for (expected in listOf(config, key, slice)) assertArrayEquals(byteArrayOf(0) + expected, early.frame()!!.second)
        val late = join(access)
        late.text()
        for (expected in listOf(config, key, slice)) assertArrayEquals(byteArrayOf(0) + expected, late.frame()!!.second)
        b.release()
    }

    private val media = AaAudioFeed.Pcm(AaAudioFeed.Stream.MEDIA, byteArrayOf(1, 2, 3, 4))
    private val voice = AaAudioFeed.Pcm(AaAudioFeed.Stream.GUIDANCE, byteArrayOf(5, 6))

    @Test
    fun `with the sound in the browser, the viewers get it with its tag, the music with a 1 and the guide's voice with a 2`() {
        for (mode in listOf(WebAudioMode.BROWSER, WebAudioMode.BOTH)) {
            val b = bridge()
            b.apply(webView = true, webTouch = false, webAudio = mode)
            val c = join(WebAccess.state.value!!)
            c.text()
            b.audio(media)
            b.audio(voice)
            assertArrayEquals(mode.name, byteArrayOf(1, 1, 2, 3, 4), c.frame()!!.second)
            assertArrayEquals(mode.name, byteArrayOf(2, 5, 6), c.frame()!!.second)
            b.release()
        }
    }

    @Test
    fun `with the sound on the phone only, nothing of it goes to the browser`() {
        val b = bridge()
        b.apply(webView = true, webTouch = false, webAudio = WebAudioMode.PHONE)
        val c = join(WebAccess.state.value!!)
        c.text()
        b.audio(media)
        b.audio(voice)
        b.androidAutoChanged(active = true)
        assertEquals("only the state message comes", "{\"state\":\"live\",\"touch\":false}", c.text())
        b.release()
    }

    @Test
    fun `sound that comes with the server down goes nowhere and does no harm`() {
        val b = bridge()
        b.audio(media)
        b.apply(webView = true, webTouch = false, webAudio = WebAudioMode.BOTH)
        b.release()
        b.audio(voice)
        assertNull(WebAccess.state.value)
    }

    @Test
    fun `the video stops 10 seconds after the last viewer, and the server goes on`() {
        val b = bridge()
        val access = running(b)
        val c = join(access)
        c.text()
        eventually("video started") { source.starts == 1 }
        // Counted first: the video starts on the join, before the count says 1, and a count of 0 seen before that is not the viewer leaving.
        eventually("one viewer") { WebAccess.state.value?.viewers == 1 }
        c.socket.close()
        eventually("no viewer") { WebAccess.state.value?.viewers == 0 }
        delayer.advance(9_999)
        assertEquals(0, source.stops)
        delayer.advance(1)
        assertEquals(1, source.stops)
        assertNotNull("the server still answers", WebAccess.state.value)
        b.release()
    }

    @Test
    fun `kick from the screen drops every viewer`() {
        val b = bridge()
        val c = join(running(b))
        c.text()
        eventually("one viewer") { WebAccess.state.value?.viewers == 1 }
        WebAccess.kickAll()
        assertNull(c.frame())
        eventually("none") { WebAccess.state.value?.viewers == 0 }
        b.release()
    }

    @Test
    fun `release closes the port, stops the video and clears what the screen shows`() {
        val b = bridge()
        val access = running(b)
        val c = join(access)
        c.text()
        eventually("video started") { source.starts == 1 }
        b.release()
        assertNull(c.frame())
        assertEquals(1, source.stops)
        assertNull(WebAccess.state.value)
        assertTrue("nobody listens", runCatching { java.net.Socket("127.0.0.1", access.port).close() }.isFailure)
    }

    @Test
    fun `the kick of an old bridge does not reach a new one`() {
        val old = bridge()
        running(old)
        old.release()
        val b = bridge()
        val c = join(running(b))
        c.text()
        WebAccess.kickAll()
        assertNull(c.frame())
        b.release()
    }

    /** Audit of 20/10/2026: Android Auto's read loop gives the sound to `audio()`, which waited behind a server that was starting or stopping. */
    @Test
    fun `the sound is handed over at once even while the server is being started`() {
        val starting = java.util.concurrent.CountDownLatch(1)
        val letGo = java.util.concurrent.CountDownLatch(1)
        val slow = WebBridge(
            WebBridge.Env(
                page = { page },
                // The server's video source is built inside start(), under the bridge's lock: here it takes its time.
                video = { out -> pushOutput = out; starting.countDown(); letGo.await(10, TimeUnit.SECONDS); source },
                sendTouch = { touches += it },
                delayer = delayer,
                log = { logs += it },
                network = WebBridge.Network(freePorts(), allowed = { true }, draw = { draws.removeFirst() }, thePhone = { false }),
            ),
        ).also { bridges += it }
        val applying = Thread { slow.apply(webView = true, webTouch = false, webAudio = WebAudioMode.BROWSER) }.apply { start() }
        try {
            assertTrue("the server never began to start", starting.await(5, TimeUnit.SECONDS))
            val took = measureNanos { slow.audio(AaAudioFeed.Pcm(AaAudioFeed.Stream.MEDIA, ByteArray(960))) }
            assertTrue("audio() waited ${took / 1_000_000} ms behind the starting server", took < TimeUnit.MILLISECONDS.toNanos(500))
            val told = measureNanos { slow.androidAutoChanged(true) }
            assertTrue("androidAutoChanged() waited ${told / 1_000_000} ms behind the starting server", told < TimeUnit.MILLISECONDS.toNanos(500))
        } finally {
            letGo.countDown()
            applying.join(10_000)
            slow.release()
        }
    }

    private fun down(x: Int, y: Int) = "{\"a\":\"d\",\"x\":$x,\"y\":$y}"

    /** The events of the server that is up now, and then of the one that replaces it after a stop: [old] is the one a late call comes from. */
    private class Generations(val old: WebServer.Events, val oldGeneration: Long)

    /** A server up and stopped, a second one up (touch on, one browser in, a stroke open); what the first one's events were is returned. */
    private fun replacedServer(b: WebBridge): Generations {
        running(b)
        val oldGeneration = b.liveGeneration
        assertNotEquals(0L, oldGeneration)
        val old = b.eventsFor(oldGeneration) { null }
        b.apply(webView = false, webTouch = false)
        val c = join(running(b, touch = true))
        c.text()
        eventually("the new server counts its viewer") { WebAccess.state.value?.viewers == 1 }
        c.sendText(down(10, 20))
        eventually("the stroke of the new server") { touches.size == 1 }
        assertNotEquals(oldGeneration, b.liveGeneration)
        return Generations(old, oldGeneration)
    }

    @Test
    fun `a late call of a server that was stopped does not reach the screen's count nor the stroke of the new one`() {
        val b = bridge()
        val g = replacedServer(b)
        g.old.onViewers(0)
        assertEquals("the old count must not replace the new one", 1, WebAccess.state.value?.viewers)
        assertEquals("the old zero must not cancel the new server's stroke", 1, touches.size)
        g.old.onViewers(7)
        assertEquals(1, WebAccess.state.value?.viewers)
        g.old.onText(down(1, 2))
        g.old.onText("{\"a\":\"m\",\"x\":30,\"y\":40}")
        assertEquals("the old server's text is not a touch", 1, touches.size)
    }

    @Test
    fun `a late join of a server that was stopped is not told the state`() {
        val b = bridge()
        val g = replacedServer(b)
        val (viewer, theirs) = loopbackViewer()
        g.old.onJoin(viewer)
        viewer.sendText("marker")
        viewer.startWriter()
        assertEquals("nothing came before the marker", "marker", theirs.text())
    }

    @Test
    fun `the events of the server that is up are heard`() {
        val b = bridge()
        val g = replacedServer(b)
        val now = b.eventsFor(b.liveGeneration) { null }
        now.onViewers(5)
        assertEquals(5, WebAccess.state.value?.viewers)
        now.onViewers(0)
        assertEquals("the zero of the server that is up cancels the open stroke", 2, touches.size)
        assertEquals(PhoneTouch.Action.CANCEL, touches[1].action)
        now.onText(down(3, 4))
        assertEquals(3, touches.size)
        val (viewer, theirs) = loopbackViewer()
        now.onJoin(viewer)
        viewer.startWriter()
        assertEquals("{\"state\":\"off\",\"touch\":true}", theirs.text())
        assertNotEquals(g.oldGeneration, b.liveGeneration)
    }

    @Test
    fun `the pin of a server that was stopped does not change the pin the screen shows`() {
        val b = bridge()
        val g = replacedServer(b)
        val shown = WebAccess.state.value!!.pin
        b.pinChanged(g.oldGeneration, "9999")
        assertEquals(shown, WebAccess.state.value!!.pin)
        b.pinChanged(b.liveGeneration, "9998")
        assertEquals("9998", WebAccess.state.value!!.pin)
    }

    @Test
    fun `the server is the live one from the moment it listens, so a browser that comes in at once is heard`() {
        val b = bridge()
        running(b)
        assertEquals(listOf(b.liveGeneration), liveWhenListening.toList())
        assertNotEquals(0L, b.liveGeneration)
    }

    @Test
    fun `a server that is released is not heard, and one that found no port is not either`() {
        val b = bridge()
        running(b)
        val generation = b.liveGeneration
        b.release()
        assertEquals(0L, b.liveGeneration)
        b.pinChanged(generation, "9999")
        assertNull(WebAccess.state.value)
        ServerSocket(0).use {
            val none = bridge(ports = it.localPort..it.localPort)
            none.apply(webView = true, webTouch = false)
            assertEquals(0L, none.liveGeneration)
        }
    }

    @Test
    fun `Android Auto going and coming back leaves the count of the browsers right`() {
        val b = bridge()
        repeat(10) { draws.addLast(2000 + it) }
        for (round in 1..5) {
            val c = join(running(b))
            c.text()
            // The server goes down and up again (what the service does when Android Auto's session ends and returns): the tellings of
            // the first one are still on their way when the second one is up.
            b.androidAutoChanged(active = round % 2 == 1)
            b.apply(webView = false, webTouch = false)
            val d = join(running(b))
            d.text()
            eventually("every telling ended") { WebViewers.tellingsInFlight.get() == 0 }
            eventually("the new browser counted: ${WebAccess.state.value}") { WebAccess.state.value?.viewers == 1 }
        }
    }

    /** A viewer on one end of a loopback connection and a browser's side on the other; nothing of the server is in between. */
    private fun loopbackViewer(): Pair<WebViewer, WsTestClient> = ServerSocket(0).use { listener ->
        val theirs = WsTestClient(listener.localPort).also { clients += it }
        val viewer = WebViewer(listener.accept(), queueFrames = 10, onGone = {})
        loopbackViewers += viewer
        viewer to theirs
    }

    private fun measureNanos(block: () -> Unit): Long {
        val begun = System.nanoTime()
        block()
        return System.nanoTime() - begun
    }
}
