package com.reau.app.web

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/** The few answers the web server gives. An error is its status and nothing else: it never says what exists. */
internal object HttpResponses {
    private val REASONS = mapOf(
        200 to "OK", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
        429 to "Too Many Requests", 431 to "Request Header Fields Too Large", 503 to "Service Unavailable",
    )

    /** An error with no body. */
    fun empty(out: OutputStream, status: Int) = write(out, status, emptyList(), ByteArray(0))

    /** Where the page's inline `<script>` and `<style>` tags say `nonce="…"`: each response puts its own nonce there. */
    const val NONCE_MARK = "@NONCE@"

    /**
     * What the page may load and do: its own inline script and style, and only those that carry this response's [nonce] (so a
     * script injected into the page, which cannot know it, does not run), its own address, the video's blob and the favicon's
     * data address, and the socket to the phone (a WebSocket scheme, which `'self'` does not cover in every browser). No
     * plug-ins, no frames around it, no `<base>`, no form posts. It keeps another site's script out of a page that holds the PIN.
     */
    fun policy(nonce: String) = "default-src 'self' data: blob: ws: wss:; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /** A new nonce: 128 random bits in base64, which the browser takes as it is (no `+`, `/` or `=`). */
    fun newNonce(random: SecureRandom = RANDOM): String {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The page: HTML, never cached, and a PIN in its address never leaves in a Referer. [body] says [NONCE_MARK] in each inline
     * tag; the answer carries them with a nonce of its own, new every time, and the policy that allows only it.
     */
    fun page(out: OutputStream, body: ByteArray, nonce: String = newNonce()) = write(
        out, 200,
        listOf(
            "Content-Type: text/html; charset=utf-8", "Referrer-Policy: no-referrer", "X-Content-Type-Options: nosniff",
            "Content-Security-Policy: ${policy(nonce)}",
        ),
        fillNonce(body, nonce),
    )

    /** [body] with each [NONCE_MARK] written as [nonce], byte for byte (the page's other bytes, UTF-8 text included, stay as they are). */
    internal fun fillNonce(body: ByteArray, nonce: String): ByteArray {
        val mark = NONCE_MARK.toByteArray(Charsets.US_ASCII)
        val with = nonce.toByteArray(Charsets.US_ASCII)
        val result = java.io.ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            if (body[i] == mark[0] && i + mark.size <= body.size && mark.indices.all { body[i + it] == mark[it] }) {
                result.write(with)
                i += mark.size
            } else {
                result.write(body[i].toInt())
                i++
            }
        }
        return result.toByteArray()
    }

    /** The answer that turns the connection into a WebSocket. */
    fun upgrade(out: OutputStream, accept: String) {
        val head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /**
     * Closes [socket] after the answer without a reset: an answer sent while the client's bytes were still unread would be
     * lost to a reset, so the output is closed first and what the client still sends is read (briefly) and thrown away.
     */
    fun closeAfterAnswer(socket: Socket, input: InputStream) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = LINGER_MS
            val drain = ByteArray(DRAIN_BYTES)
            var left = LINGER_BYTES
            while (left > 0 && input.read(drain) >= 0) left -= drain.size
        }
        runCatching { socket.close() }
    }

    private fun write(out: OutputStream, status: Int, extra: List<String>, body: ByteArray) {
        val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n")
        extra.forEach { head.append(it).append("\r\n") }
        head.append("Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private val RANDOM = SecureRandom()
    private const val NONCE_BYTES = 16
    private const val LINGER_MS = 200
    private const val LINGER_BYTES = 64 * 1024
    private const val DRAIN_BYTES = 4096
}
