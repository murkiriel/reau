package com.reau.app.web

import com.reau.app.aa.AaAudioFeed
import com.reau.app.aa.PhoneTouch
import com.reau.app.settings.WebAudioMode
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * The web server tied to the projection. The service gives it the rider's two switches ([apply]) and Android Auto's
 * state ([androidAutoChanged]); it brings the server and the video up and down, tells every viewer what is going on, and
 * hands the browser's touch to Android Auto, one stroke at a time, only while the touch switch is on. What the screen shows
 * goes to [WebAccess]. It never writes the PIN or an address to the log.
 */
internal class WebBridge(private val env: Env) {
    class Env(
        val page: () -> ByteArray,
        /** The video source for the stream; [onOutput] is where it gives what the encoder produces. */
        val video: (onOutput: (ByteArray, Boolean, Boolean) -> Unit) -> WebVideoSource,
        /** The browser's touch, as a point of Android Auto's picture, on its way to Android Auto. */
        val sendTouch: (PhoneTouch.Sent) -> Unit,
        val delayer: Delayer,
        val log: (String) -> Unit = {},
        val network: Network = Network(),
    )

    /** Where the server may listen, who may reach it and how many wrong PINs it bears. */
    class Network(
        val ports: IntRange = 8780..8789,
        val allowed: (InetAddress) -> Boolean = LocalNetwork::isAllowed,
        val thePhone: (InetAddress) -> Boolean = LocalNetwork::isThePhone,
        val limits: WebGuard.Limits = WebGuard.Limits(),
        val draw: () -> Int = WebGuard.systemDraw,
    )

    private class Running(val server: WebServer, val stream: WebStream)

    // Held by apply() and release() alone, which start and stop the server and so can take a while. What the other threads read
    // (Android Auto's read loop with the sound, a viewer joining, the browser's touch) is volatile, so none of them waits behind
    // a server that is starting or stopping (audit of 20/10/2026: pings and the picture's acknowledgements were delayed).
    private val lock = Any()
    @Volatile private var running: Running? = null

    // The generation of the server that is up, or NONE. Each server's events and PIN callback carry the generation they were made
    // for and do nothing once it is not this one: a telling of a server already stopped (the viewers' count goes to the events on a
    // thread of its own, after the fact) must not reach the screen's count, the touch stroke or the PIN of the server that replaced it.
    @Volatile private var live = NONE
    @Volatile private var touchOn = false
    @Volatile private var audioOn = false
    @Volatile private var androidAutoOn = false
    private val strokes = WebStrokes(env.sendTouch)

    /**
     * The rider's switches: the server up or down, the touch allowed or not, and where the sound goes ([webAudio]: the browser gets it
     * in BROWSER and BOTH). Each change is told to the viewers.
     */
    fun apply(webView: Boolean, webTouch: Boolean, webAudio: WebAudioMode = WebAudioMode.PHONE) = synchronized(lock) {
        val touchChanged = touchOn != webTouch
        touchOn = webTouch
        if (!webTouch) strokes.cancel()
        audioOn = webAudio != WebAudioMode.PHONE
        if (webView) {
            if (running == null) start() else if (touchChanged) tell()
        } else {
            stop()
        }
    }

    /** Android Auto's session is up ([active]) or not. */
    fun androidAutoChanged(active: Boolean) {
        if (androidAutoOn == active) return
        androidAutoOn = active
        tell()
    }

    /** A piece of Android Auto's sound: to every viewer when the sound is to go to the browser, otherwise nowhere. */
    fun audio(pcm: AaAudioFeed.Pcm) {
        val server = if (audioOn) running?.server else null
        server?.publish(WebFrames.audio(pcm), sound = true)
    }

    fun release() = synchronized(lock) { stop() }

    private fun status(): String =
        WebProtocol.status(if (androidAutoOn) WebProtocol.State.LIVE else WebProtocol.State.OFF, touchOn)

    private fun tell() {
        running?.server?.sendText(status())
    }

    private fun start() {
        val generation = generations.incrementAndGet()
        val guard = WebGuard(env.network.limits, draw = env.network.draw) { changed -> pinChanged(generation, changed) }
        var stream: WebStream? = null
        val source = env.video { bytes, key, config -> stream?.output(bytes, key, config) }
        val settings = WebServer.Settings(guard, env.network.ports, allowed = env.network.allowed, thePhone = env.network.thePhone)
        val server = WebServer(settings, eventsFor(generation) { stream })
        stream = WebStream(source, { server.publish(WebFrames.video(it)) }, env.delayer, { server.viewerCount }, env.log)
        // Before the server listens: a browser that comes in at once must not be taken for one of an older server.
        live = generation
        val port = server.start()
        if (port == null) {
            live = NONE
            env.log("web: no free port in ${env.network.ports}")
            return
        }
        running = Running(server, stream)
        // "Derrubar todos": a new PIN first, so the browsers put out cannot come straight back with the old one.
        WebAccess.publish(WebAccess.Access(port, guard.pin, 0)) {
            WebAccess.pin(guard.renew())
            server.kick()
        }
    }

    private fun stop() {
        live = NONE
        strokes.cancel()
        val r = running ?: return
        running = null
        r.stream.shutdown()
        r.server.stop()
        WebAccess.clear()
    }

    /** The PIN of the server of [generation] changed after too many wrong ones: the screen shows it, unless that server is gone. */
    internal fun pinChanged(generation: Long, pin: String) {
        if (generation != live) return
        env.log("web: the pin changed after too many wrong ones")
        WebAccess.pin(pin)
    }

    /** The generation of the server that is up now, or [NONE]. */
    internal val liveGeneration: Long get() = live

    internal fun eventsFor(generation: Long, stream: () -> WebStream?) = object : WebServer.Events {
        override fun page(): ByteArray = env.page()

        override fun onJoin(viewer: WebViewer) {
            if (generation != live) return
            viewer.sendText(status())
            stream()?.viewerJoined { viewer.sendBinary(WebFrames.video(it)) }
        }

        override fun onText(text: String) {
            if (generation != live) return
            if (touchOn) strokes.take(text)
        }

        override fun onViewers(count: Int) {
            if (generation != live) return
            if (count == 0) strokes.cancel()
            stream()?.viewersChanged(count)
            WebAccess.viewers(count)
        }

        override fun log(line: String) = env.log(line)
    }

    private companion object {
        const val NONE = 0L

        // Global, so that the generations of two bridges (a service that was recreated) never repeat.
        val generations = AtomicLong()
    }
}
