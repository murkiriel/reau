package com.reau.app.dash

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Whether another app holds the dash's port.
 *
 * Another dash app may open UDP 2002 with address reuse on, like [DashSocket] does, so both could bind it at once
 * and the dash's packets would go to one or the other. A bind WITHOUT reuse fails while any socket holds the
 * port, whatever its options: that is the check. It holds the port only for the instant of the bind.
 */
object DashPorts {
    /** Where the dash answers the phone ([DashSocket.RX_PORT]). */
    const val DASH_RX_PORT = DashSocket.RX_PORT

    fun isFree(port: Int): Boolean = bindFailure(port) == null

    /**
     * Why a bind of [port] without address reuse fails: the exception's class and message (Android's message carries the errno, such as
     * `EADDRINUSE`), or null when it binds. The words are the system's own and name no one; they are for the ride log.
     */
    fun bindFailure(port: Int): String? = try {
        DatagramSocket(null).use {
            it.reuseAddress = false
            it.bind(InetSocketAddress(port))
        }
        null
    } catch (e: SocketException) {
        // A BindException (EADDRINUSE) is one; any other is a socket the dash session could not open either.
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * [isFree] of the dash's receive port, which decides, with the failure told through [say] (a line for the ride log) and the dash's send port
     * checked as well, for the log only: the answer is the receive port's alone. Told only when something fails, so a free port adds nothing.
     */
    fun receivePortFree(say: (String) -> Unit): Boolean {
        val rx = bindFailure(DASH_RX_PORT)
        if (rx == null) return true
        val tx = bindFailure(DashSocket.CTRL_PORT)
        say("could not bind UDP $DASH_RX_PORT (${rx}); UDP ${DashSocket.CTRL_PORT} " + (tx?.let { "fails too ($it)" } ?: "binds"))
        return false
    }
}
