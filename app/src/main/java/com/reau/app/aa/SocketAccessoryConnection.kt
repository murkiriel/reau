// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/SocketAccessoryConnection.kt, commit 0abbe2a.
// Changes: package; the header comment said who connects to whom in OpenCfMoto (here this app dials Android Auto's
// developer head unit server on 127.0.0.1:5277 and hands the connected socket over). The code is unchanged.
// Adapted from headunit-revived (AGPLv3): connection/SocketAccessoryConnection.kt
// Trimmed to a pre-connected loopback socket (self-mode): no network binding, no outbound
// connect() of its own.
// Changed since: a failed send is one warning line (the link's end has its own line), not an error with a stack.
package com.reau.app.aa

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The warning for a send that failed: one line with the reason and no stack. Android Auto closing a connection it does not accept yet
 * (a broken pipe at the first message) was an `E:` with a stack at every try (20/10/2026); the end of the link has its own line.
 */
internal fun sendFailedLine(e: IOException): String = "send failed: ${e.javaClass.simpleName}: ${e.message}"

class SocketAccessoryConnection(private val socket: Socket) : AccessoryConnection {
    private var output: OutputStream? = null
    private var input: DataInputStream? = null

    init {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (_: Exception) {}
        if (socket.isConnected) {
            try {
                input = DataInputStream(socket.getInputStream())
                output = socket.getOutputStream()
            } catch (e: IOException) {
                AaLog.e("Failed to get streams from pre-connected socket", e)
            }
        }
    }

    /** HUR loopback/Nearby sockets read one full AAP message at a time. */
    override val isSingleMessage: Boolean get() = true

    override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    override fun connect(): Boolean = socket.isConnected

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val out = output ?: return -1
        return try {
            synchronized(out) {
                out.write(buf, 0, length)
                out.flush()
            }
            length
        } catch (e: IOException) {
            AaLog.w(sendFailedLine(e))
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val inp = input ?: return -1
        return try {
            try { socket.soTimeout = timeout } catch (_: Exception) {}
            if (readFully) {
                inp.readFully(buf, 0, length)
                length
            } else {
                inp.read(buf, 0, length)
            }
        } catch (_: SocketTimeoutException) {
            0
        } catch (_: IOException) {
            -1
        }
    }

    override fun disconnect() {
        try { socket.close() } catch (_: IOException) {}
        input = null
        output = null
    }
}
