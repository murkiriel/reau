package com.reau.app.dash

import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * One cheap question to the dash right after it went silent, while the phone is still joined to its Wi-Fi (0.3.99 test build, for the ride log only; nothing reads the answer):
 * does the dash's IP stack still answer? A TCP connect to a port the dash does not serve, through the dash's own network, with a short timeout, tells three things apart with a
 * single SYN: refused (the dash sent a reset, so its network stack is alive and only the app's side went quiet), no answer (its Wi-Fi or its network stack is dead) and no route.
 * An open port tells it is alive too. Runs once per silence, on a background thread, and never holds the reconnection.
 */
internal object PanelProbe {
    /** A port the dash is not known to serve (its own are UDP). */
    const val PORT = 2001
    const val TIMEOUT_MS = 1_000

    enum class Verdict(val words: String) {
        OPEN("connected: the dash's network stack answers (the port is open)"),
        REFUSED("refused: the dash's network stack answers, only the app's traffic went quiet"),
        TIMEOUT("no answer: the dash's Wi-Fi or network stack looks dead"),
        NO_ROUTE("no route: the phone cannot reach the dash's address at all"),
        ERROR("could not be tried"),
    }

    class Result(val verdict: Verdict, val tookMs: Long, val detail: String?)

    /** What the failure [e] of the connect says; null (it connected) is [Verdict.OPEN]. The system's own words are the evidence (Android's messages carry the errno). */
    fun classify(e: Throwable?): Verdict {
        if (e == null) return Verdict.OPEN
        val words = e.message.orEmpty()
        return when {
            e is SocketTimeoutException || words.contains("ETIMEDOUT") -> Verdict.TIMEOUT
            words.contains("ECONNREFUSED") || words.contains("refused", ignoreCase = true) -> Verdict.REFUSED
            e is NoRouteToHostException || words.contains("ENETUNREACH") || words.contains("EHOSTUNREACH") ||
                words.contains("unreachable", ignoreCase = true) -> Verdict.NO_ROUTE
            else -> Verdict.ERROR
        }
    }

    /** Runs [connect] (which throws when the connection fails) and times it with [nowNs]. */
    @Suppress("TooGenericExceptionCaught") // whatever the connect throws is the answer
    fun attempt(nowNs: () -> Long = System::nanoTime, connect: () -> Unit): Result {
        val start = nowNs()
        val failure = try {
            connect()
            null
        } catch (e: Exception) {
            e
        }
        val took = (nowNs() - start) / NS_PER_MS
        val verdict = classify(failure)
        // A timeout and a reset say it all; the words of the others (the errno) are the evidence.
        val detail = failure?.takeIf { verdict == Verdict.NO_ROUTE || verdict == Verdict.ERROR }?.let { "${it.javaClass.simpleName}: ${it.message}" }
        return Result(verdict, took, detail)
    }

    /** The probe through [network] (the dash's own): one TCP connect to the dash's address and [PORT]. Blocks up to [TIMEOUT_MS]: call it off the main thread. */
    fun probe(network: android.net.Network): Result = attempt {
        network.socketFactory.createSocket().use { it.connect(InetSocketAddress(DashSocket.DASH_IP, PORT), TIMEOUT_MS) }
    }

    /** The ride log's line for [r], [silentS] s after the dash was last heard. */
    fun line(r: Result, silentS: Long): String =
        "panel probe after $silentS s of silence (TCP ${DashSocket.DASH_IP}:$PORT through the dash's network, up to $TIMEOUT_MS ms): " +
            "${r.verdict.words}, in ${r.tookMs} ms" + (r.detail?.let { " ($it)" } ?: "")

    /** The line when the dash's network was already gone at the watchdog, so there was nothing to ask. */
    fun skippedLine(silentS: Long): String = "panel probe after $silentS s of silence skipped: the phone's network to the dash is already gone"

    private const val NS_PER_MS = 1_000_000L
}
