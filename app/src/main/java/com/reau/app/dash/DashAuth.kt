package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.dash.protocol.Tlv
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** Result of feeding one incoming TLV into the auth state machine. */
sealed class AuthEvent {
    /** Both pubkey halves received — send this session-key packet now. */
    data class SendKey(val packet: ByteArray) : AuthEvent()
    /** Dash confirmed (07 01 01). */
    object Confirmed : AuthEvent()
    /** Dash rejected (07 01 != 01) — resend authRequest if retries remain. */
    object Rejected : AuthEvent()
    object None : AuthEvent()
}

/**
 * RSA-1024 + AES-256 handshake state machine, ported from better-dash AuthState.
 *
 * The dash sends modulus (07 00) and exponent (07 03) — possibly in SEPARATE
 * packets — so state accumulates across calls. The session-key packet is
 * emitted exactly once per attempt; [reset] re-arms it after a rejection.
 */
class DashAuth(private val ssid: String) {
    private var modulus: BigInteger? = null
    private var exponent: BigInteger? = null
    private var keySent = false

    var sessionKey: ByteArray? = null
        private set

    /** The dash has sent (a half of) its public key in this attempt, or the session key already went out. */
    val keySeen: Boolean get() = keySent || modulus != null || exponent != null

    fun ingest(tlv: Tlv): AuthEvent {
        if (tlv.type != 0x07) return AuthEvent.None
        return when (tlv.sub) {
            // The first half of each kind in an attempt is the one kept: a second one (a copy from somebody else on the
            // dash's Wi-Fi, racing the dash) does not replace it. A rejection ([reset]) starts the attempt over.
            0x00 -> {
                if (modulus == null) modulus = BigInteger(1, tlv.value)
                offerKey()
            }
            0x03 -> {
                if (exponent == null) exponent = BigInteger(1, tlv.value)
                offerKey()
            }
            0x01 -> if (tlv.value.firstOrNull() == 0x01.toByte()) AuthEvent.Confirmed else AuthEvent.Rejected
            else -> AuthEvent.None
        }
    }

    /** Sends the session key once both halves of the dash's public key are in. */
    private fun offerKey(): AuthEvent {
        val m = modulus; val e = exponent
        if (keySent || m == null || e == null) return AuthEvent.None
        // m/e come straight off the wire (BigInteger(1, bytes) never throws, however
        // degenerate the bytes) — but a corrupt packet or noisy RF can hand us a modulus
        // too small/malformed for RSA, which KeyFactory/Cipher below reject by throwing.
        // Never let that escape into the RX loop's caller uncaught (it has no try/catch
        // around dispatch — an uncaught exception here crashes the whole app). Drop both
        // halves and wait for the dash to (re)send a valid pubkey instead.
        val packet = try {
            buildKeyPacket(m, e)
        } catch (_: Exception) {
            null
        }
        return if (packet == null) {
            modulus = null; exponent = null
            AuthEvent.None
        } else {
            keySent = true
            AuthEvent.SendKey(packet)
        }
    }

    /** Re-arm after a 07 01 != 01 rejection so the dash can re-offer its pubkey. */
    fun reset() {
        modulus = null
        exponent = null
        keySent = false
        // The key the dash refused is no key: the next attempt makes its own.
        sessionKey = null
    }

    private fun buildKeyPacket(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val payload = ssid.toByteArray(Charsets.UTF_8) + aes
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val packet = DashCommands.authSendKey(cipher.doFinal(payload))
        // Only now: the key is the dash's once it has gone into a packet. Set before, a modulus the cipher refused left behind a
        // session key the dash never got, and the call card (encrypted with it) would have been unreadable to it (audit of 20/10/2026).
        sessionKey = aes
        return packet
    }
}
