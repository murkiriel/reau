package com.reau.app.dash

import java.net.InetAddress

/**
 * When Android refuses to bind a socket to the dash's network (EPERM) while that network is alive (known to Android, Wi-Fi: it joined the dash
 * by itself while REau's request was open), REau tries **once** to open the sockets on the phone's own IPv4 address on that network, without the
 * binding, before it gives the network up. Nobody can promise that Android routes such a socket through the dash's Wi-Fi: so the route is proved
 * before it is used (the dash must answer **our greeting** with its RSA key within [PROOF_MS] of the moment it is ready for it, [proofStartMs]: its own heartbeat and telemetry arrive with or without the binding, and prove only the way in) and a try that proves nothing ends as the refusal always did
 * ([DashSession.onBindResult] with `refused`, the network dropped and asked for again later). A dead network, a network with no IPv4 address,
 * a second try for the same refusal and every bind that works are left as they were. The address itself is never written to the ride log (it is
 * a private address of the phone): the lines say "the local address". Pure, so the JVM tests cover the decisions.
 */
object LocalAddressRetry {
    /**
     * How long the dash has to answer the greeting (with its key) through the local address, counted from [proofStartMs]: a dash that has just turned on (the
     * case of an Android that joins it by itself right after the ignition) gives no key before its heartbeat counter reaches [DashReadiness.READY_COUNT].
     */
    const val PROOF_MS = 5_000L

    /** What the phone knows of the network that refused the bind: [alive] (Android knows it and it is Wi-Fi; see [JoinedNetwork.usable]) and its [ipv4] address on it, if any. */
    data class Facts(val alive: Boolean, val ipv4: InetAddress?)

    /** No fact: no try. */
    val NONE = Facts(alive = false, ipv4 = null)

    /** Whether the one try goes: only for a refused bind ([bindRefused]) on a network that is [alive] and has an IPv4 address, and not twice for the same refusal. */
    fun shouldTry(bindRefused: Boolean, alive: Boolean, hasIpv4: Boolean, alreadyTried: Boolean): Boolean =
        bindRefused && alive && hasIpv4 && !alreadyTried

    /**
     * The moment the time of the proof starts: the first greeting ([burstStartMs]) or the dash being ready for it, the later. [first] is the heartbeat counter seen at
     * [firstAtMs] (null: none seen, the dash counts as ready). The wait for a young dash is not a silence: it goes on through the greetings every second of the
     * wait, up to the time to authenticate ([DashReadiness.deadlineMs]), which is where this try ends too.
     */
    fun proofStartMs(burstStartMs: Long, first: Int?, firstAtMs: Long): Long =
        maxOf(burstStartMs, DashReadiness.readyAtMs(first, firstAtMs) ?: burstStartMs)

    /** What the wait says: not yet, the route works, or it does not. */
    enum class Proof { WAIT, WORKED, DID_NOT_ANSWER }

    /**
     * The proof of the route, which has to cover the way out too: [answeredGreeting] (the dash's RSA key arrived, or the key exchange is already done), [sinceGreetingMs]
     * since the proof started ([proofStartMs]; negative while a young dash is not ready yet). A packet that is not that answer (the heartbeat the dash sends by itself) proves nothing.
     */
    fun proof(answeredGreeting: Boolean, sinceGreetingMs: Long, limitMs: Long = PROOF_MS): Proof = when {
        answeredGreeting -> Proof.WORKED
        sinceGreetingMs >= limitMs -> Proof.DID_NOT_ANSWER
        else -> Proof.WAIT
    }

    const val TRYING_LINE = "EPERM on the dash's network: trying once through its local address"
    const val WORKED_LINE = "EPERM on the dash's network, but the dash answered our greeting through the local address: the session goes on without the binding"

    fun couldNotOpenLine(why: String) = "the sockets could not be opened on the local address ($why): the refusal stands, as before"

    fun silentLine(afterMs: Long) =
        "the dash did not answer through the local address in $afterMs ms (from its being ready for the greeting): " +
            "the refusal stands, the network is dropped and the dash is tried again later (as before)"

    fun failedLine(afterMs: Long) =
        "the session through the local address failed ${afterMs / MS_PER_S} s after the first greeting: the refusal stands, as before"

    private const val MS_PER_S = 1_000L
}
