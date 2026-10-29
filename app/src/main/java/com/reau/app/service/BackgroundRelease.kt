package com.reau.app.service

import kotlin.concurrent.thread

/**
 * Lets go of heavy things off the main thread. The service's onDestroy runs on it, and releasing the sides there (the
 * video compositor's GL thread, the codecs' surfaces, the Wi-Fi) froze the app's screen for 5 s after Desligar:
 * `Choreographer: Skipped 301 frames`, `Davey! duration=5040ms` at 22:35:58 on 19/10/2026, 5 s after the stop was tapped.
 * What the release does is the same, only not under the rider's finger; a failure in it costs a line in the log, never the caller.
 */
internal object BackgroundRelease {
    /** Runs [block] on a thread of its own, named [name]; a throw is given to [onFailure]. Returns at once. */
    fun run(name: String, onFailure: (Throwable) -> Unit, block: () -> Unit): Thread =
        thread(name = name, isDaemon = true) {
            try {
                block()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                onFailure(e)
            }
        }
}
