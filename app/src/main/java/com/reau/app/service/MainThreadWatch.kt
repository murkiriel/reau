package com.reau.app.service

import android.os.Handler
import android.os.Looper
import com.reau.app.data.RideDiagnostics
import com.reau.app.util.MainStallLog
import com.reau.app.util.MonotonicClock

/**
 * Tells the ride log (`[mainthread]` lines) when the app's main thread stops answering, while the projection runs: if Android later closes REau for an
 * ANR, the log says whether it was REau's main thread that was stuck, for how long and where (a report of 25/11/2026). A daemon thread of its own posts a
 * tiny task to the main thread once a second and looks twice a second at whether it came back; [MainStallLog] decides what to say. It never waits on the
 * main thread (only a volatile number is written by it), costs one `post` and a clock reading a second, and is nowhere near the video's path; a line can
 * never end the projection: every look and every line is inside a `runCatching`. The slow frames of the interface are [com.reau.app.util.FrameJank]'s,
 * a different thing (a frame over 100 ms; this is the thread not answering at all for 1 s or more).
 *
 * The clock, the way to post to the main thread, the main thread's stack and the log are given, so that the JVM tests drive [tick] by hand.
 */
class MainThreadWatch internal constructor(
    private val clock: () -> Long,
    private val postToMain: (Runnable) -> Unit,
    private val mainStack: () -> List<StackTraceElement>,
    private val write: (String) -> Unit,
) {
    private var log = MainStallLog()

    /** When the main thread last ran the ping; written by it, read by the watch thread. */
    @Volatile private var answeredAt = 0L

    /** When the ping now waiting was posted (0 = none waiting); only the watch thread touches it. */
    private var sentAt = 0L
    private var lastSentAt = 0L
    @Volatile private var thread: Thread? = null

    /** One look: tells the main thread's answer or how late it is, and posts the next ping when a second has passed since the last. */
    internal fun tick() {
        val now = clock()
        if (sentAt != 0L && answeredAt >= sentAt) {
            tell(log.answered(answeredAt - sentAt))
            sentAt = 0L
        }
        if (sentAt != 0L) {
            tell(log.waiting(now - sentAt) { MainStallLog.stackText(runCatching { mainStack() }.getOrDefault(emptyList())) })
        } else if (now - lastSentAt >= PING_PERIOD_MS) {
            sentAt = now
            lastSentAt = now
            postToMain(Runnable { answeredAt = clock() })
        }
    }

    /** One line of the `mainthread` tag; null (nothing to say) writes nothing. */
    private fun tell(line: String?) {
        if (line != null) runCatching { write(line) }
    }

    /** Starts the watch thread; a second start while it runs does nothing. */
    @Synchronized
    fun start() {
        if (thread != null) return
        // A new projection starts from nothing: no ping waiting, no stall told.
        log = MainStallLog()
        sentAt = 0L
        lastSentAt = 0L
        runCatching {
            Thread({ loop() }, THREAD_NAME).apply { isDaemon = true }.also { thread = it }.start()
        }.onFailure { thread = null }
    }

    /** Stops the watch thread. Safe to call when it never started. */
    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val mine = Thread.currentThread()
        while (thread === mine) {
            runCatching { tick() }
            if (runCatching { Thread.sleep(TICK_MS) }.isFailure) return
        }
    }

    companion object {
        private const val THREAD_NAME = "reau-main-watch"
        private const val TAG = "mainthread"

        /** How often the thread looks, and how often it posts a ping to the main thread. */
        const val TICK_MS = 500L
        const val PING_PERIOD_MS = 1_000L

        private val shared by lazy {
            val main = Handler(Looper.getMainLooper())
            MainThreadWatch(
                clock = MonotonicClock::nowMs,
                postToMain = { main.post(it) },
                mainStack = { Looper.getMainLooper().thread.stackTrace.toList() },
                write = { RideDiagnostics.log(TAG, it) },
            )
        }

        /** Starts the watch for this projection (the one every projection shares). */
        fun begin() {
            runCatching { shared.start() }
        }

        /** Gives the watch back at the projection's release. */
        fun end() {
            runCatching { shared.stop() }
        }
    }
}
