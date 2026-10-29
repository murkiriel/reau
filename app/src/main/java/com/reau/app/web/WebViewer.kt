package com.reau.app.web

import com.reau.app.util.MonotonicClock
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One browser on the WebSocket. What is sent to it waits in a bounded queue and goes out on a thread of its own,
 * so a slow browser never holds back the others nor the encoder: when its queue is full it is dropped, not waited for.
 * The server's [WebServer.Events] gets one in `onJoin` to queue what a new viewer must see first.
 */
class WebViewer internal constructor(
    private val socket: Socket,
    queueFrames: Int,
    private val pingMs: Long = PING_MS,
    private val onGone: (WebViewer) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>(queueFrames)
    private val soundLimit = maxOf(1, queueFrames / 2)
    private val closed = AtomicBoolean(false)

    // Held while a frame is being written, so that a Close sent from the reading thread never lands in the middle of the writer's frame.
    private val outLock = ReentrantLock()

    /** Set by the server once [WebServer.Events] knows of the viewer: only then does the broadcast reach it. */
    @Volatile internal var live = false

    /** True for a browser on the phone itself: it gets no sound ([LocalNetwork.isThePhone]). */
    @Volatile internal var soundOff = false

    /** Queues a binary message; false if the viewer is gone or too slow (and was dropped by this call). */
    fun sendBinary(bytes: ByteArray): Boolean = enqueue(WebSocketCodec.encode(WebSocketCodec.BINARY, bytes))

    /** Queues a text message; same answer as [sendBinary]. */
    fun sendText(text: String): Boolean = enqueue(WebSocketCodec.encode(WebSocketCodec.TEXT, text.toByteArray()))

    /**
     * Queues a frame already encoded (the broadcast encodes once for all). A full queue drops the viewer, except for [droppable] frames
     * (the sound): with the queue half full they are let go instead, so a stall of the network costs a bit of sound, not the viewer.
     */
    internal fun enqueue(frame: ByteArray, droppable: Boolean = false): Boolean = when {
        closed.get() -> false
        droppable && queue.size >= soundLimit -> true
        else -> queue.offer(frame) || fail()
    }

    private fun fail(): Boolean {
        close()
        return false
    }

    internal fun startWriter() {
        Thread(::write, "web-viewer-writer").apply { isDaemon = true }.start()
    }

    private fun write() {
        try {
            // Inside the try: a browser that closed the connection before this thread began leaves a socket with no output stream.
            val out = socket.getOutputStream()
            var pinged = MonotonicClock.nowMs()
            while (!closed.get()) {
                val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                // A ping every [pingMs] whatever else goes out: the browser's answer is what tells the reading side that a viewer
                // which never sends anything (it only watches) is still there.
                val pingDue = MonotonicClock.nowMs() - pinged >= pingMs
                if (pingDue) pinged = MonotonicClock.nowMs()
                if (frame == null && !pingDue) continue
                outLock.withLock {
                    frame?.let(out::write)
                    if (pingDue) out.write(PING_FRAME)
                    out.flush()
                }
            }
        } catch (_: IOException) {
            close()
        } catch (_: InterruptedException) {
            close()
        }
    }

    /**
     * The browser asked to close: a Close frame goes back before the socket does (RFC 6455, 5.5.1), so the browser sees a clean
     * close and not a lost connection. Written on the caller's thread, at once, between two frames of the writer; if the writer is
     * stuck on a browser that stopped reading it is not waited for beyond [CLOSE_WAIT_MS].
     */
    internal fun answerClose(reply: ByteArray) {
        if (closed.get()) return
        if (!outLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) return
        try {
            socket.getOutputStream().apply {
                write(reply)
                flush()
            }
        } catch (_: IOException) {
            // The browser is gone already: nothing to answer.
        } finally {
            outLock.unlock()
        }
    }

    /** Ends the viewer: closes its socket (which ends the reading too) and tells the server once. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            onGone(this)
        }
    }

    internal companion object {
        const val POLL_MS = 200L
        const val CLOSE_WAIT_MS = 300L

        /** The default between pings ([WebServer.Settings.pingMs]). */
        const val PING_MS = 10_000L
        private val PING_FRAME = WebSocketCodec.encode(WebSocketCodec.PING, ByteArray(0))
    }
}
