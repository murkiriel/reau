package com.reau.app.web

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Who may reach the web server: the phone itself and the local network (10.x, 172.16-31.x, 192.168.x,
 * link-local, and IPv6's unique-local). Addresses of the internet, multicast and the unspecified one are refused before
 * a byte is read.
 */
object LocalNetwork {
    /**
     * Whether [address] is the phone's own: loopback, or an address of one of its network interfaces (a browser on the phone reaches the
     * server by its Wi-Fi address too). Whatever that browser plays, Android Auto takes it as the phone's media and sends it back to it,
     * and the sound goes round and round, so it is never sent the sound.
     */
    fun isThePhone(address: InetAddress): Boolean =
        address.isLoopbackAddress || runCatching { java.net.NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)

    /**
     * Whether the `Host` of a request names this server by a local address written out as a number (`192.168.1.50:8780`,
     * `[fd00::5]:8780`) or as `localhost`: the way the rider opens the page. A name from the internet is not, even one that a
     * DNS answer points at the phone, which is what a DNS rebinding attack needs (the browser then sends that name as both `Host`
     * and `Origin`, and they match). Nothing is looked up: only literals are turned into addresses.
     */
    fun isLocalHost(hostHeader: String): Boolean {
        val host = hostOf(hostHeader) ?: return false
        val literal = IPV4.matches(host) || (hostHeader.startsWith("[") && IPV6.matches(host))
        return host.equals("localhost", ignoreCase = true) ||
            (literal && runCatching { isAllowed(InetAddress.getByName(host)) }.getOrDefault(false))
    }

    /** The name or number in a `Host` header, without the brackets and the port; null when the header is not well formed. */
    private fun hostOf(hostHeader: String): String? {
        if (hostHeader.startsWith("[")) {
            val close = hostHeader.indexOf(']')
            return if (close >= 0 && PORT.matches(hostHeader.substring(close + 1))) hostHeader.substring(1, close) else null
        }
        return when (hostHeader.count { it == ':' }) {
            0 -> hostHeader
            1 -> hostHeader.substringBefore(':').takeIf { PORT.matches(":" + hostHeader.substringAfter(':')) }
            else -> null
        }
    }

    private val PORT = Regex("""(:\d{1,5})?""")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9A-Fa-f:.]+(%[0-9A-Za-z._-]+)?""")

    /**
     * What the PIN guard counts wrong tries by: the address for IPv4, and the /64 for IPv6. A phone or laptop on IPv6 holds many
     * addresses at once (a stable one and temporary "privacy" ones that change), all in the same /64; counted one by one a single
     * browser could try the PIN from each of them with a fresh count, and locking one left the others free.
     */
    fun clientKey(address: InetAddress): String =
        if (address is Inet6Address) {
            address.address.take(IPV6_PREFIX_BYTES).joinToString("") { "%02x".format(java.util.Locale.US, it.toInt() and 0xFF) } + "/64"
        } else {
            address.hostAddress.orEmpty()
        }

    private const val IPV6_PREFIX_BYTES = 8

    fun isAllowed(address: InetAddress): Boolean = when {
        address.isAnyLocalAddress || address.isMulticastAddress -> false
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress -> true
        else -> address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }
}
