package com.reau.app.aa

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat

/**
 * Which protocol version the handshake asks Android Auto for. As always it is 1.[AaConfig.protocolMinor] (1.7). With the experimental switch
 * ([AaConfig.protocolSix], [com.reau.app.settings.REauSettings.experimentProtocolSix]) it is [SIX] (6.0) first, which other receivers already ask. A
 * version request is sent once and never again on the same connection (a duplicate leaves Android Auto's head unit server answering nobody until it
 * is restarted), so a refusal is not answered with a second request: a handshake that asked 6.0 and Android Auto answered, and that did not complete
 * (a refusal, a failed TLS step), makes every later dial of this process ask 1.7 again, and the log says so. A handshake that completed after asking
 * 6.0 keeps asking it, whatever version Android Auto answered with.
 *
 * Silence is not a refusal. With its battery restricted, Android freezes Android Auto's projection process for minutes after a session and a dial in
 * that time gets no answer at all (14/11/2026: 6.0 had worked three times that day, and a silent dial made the app ask 1.7 until it was closed). So a
 * silence counts as a failure of 6.0 only when 6.0 has never completed with the Android Auto installed now ([Proof]): the `versionCode` it completed
 * with is kept in a private file, and an updated Android Auto starts the proof again.
 */
object ProtocolAsk {
    data class Version(val major: Int, val minor: Int) {
        override fun toString(): String = "$major.$minor"
    }

    /** The version the switch asks for first. */
    val SIX = Version(6, 0)

    private const val PROOF_FILE = "protocol_ask"
    private const val KEY_PROVEN = "six_completed_version_code"

    @Volatile private var sixFailed = false

    /**
     * The proof that 6.0 works: the `versionCode` of the Android Auto it completed a handshake with ([provenOn], null: never), and the one installed now
     * ([installed], null: not known). A proof of another `versionCode` than the installed one is no proof.
     */
    interface Proof {
        fun provenOn(): Long?
        fun prove(versionCode: Long?)
        fun installed(): Long?
    }

    @Volatile private var proof: Proof? = null

    /** The proof [settle] reads and writes; null (as in the tests that do not care): there is no proof, and a silence is a failure. */
    fun use(proof: Proof?) {
        this.proof = proof
    }

    /** The proof kept in a private file of the app, and the `versionCode` of the Android Auto that [context] sees installed. */
    fun proofIn(context: Context): Proof {
        val app = context.applicationContext
        return PrefsProof(app.getSharedPreferences(PROOF_FILE, Context.MODE_PRIVATE)) { installedVersionCode(app) }
    }

    /** The `versionCode` of the installed Android Auto (its updates change it), or null when there is none. */
    private fun installedVersionCode(context: Context): Long? = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(AndroidAuto.PACKAGE, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    internal class PrefsProof(private val saved: SharedPreferences, private val installedCode: () -> Long?) : Proof {
        override fun provenOn(): Long? = if (saved.contains(KEY_PROVEN)) saved.getLong(KEY_PROVEN, 0L) else null

        override fun prove(versionCode: Long?) {
            saved.edit { if (versionCode == null) remove(KEY_PROVEN) else putLong(KEY_PROVEN, versionCode) }
        }

        override fun installed(): Long? = installedCode()
    }

    /** The version to ask: [SIX] when [wantSix] and no handshake of this process failed after asking it, else 1.[minor]. */
    fun resolve(wantSix: Boolean, minor: Int): Version = if (wantSix && !sixFailed) SIX else Version(1, minor)

    /**
     * A handshake that asked [asked] ended, [ok] if it completed; [answered] is the version Android Auto answered ("1.7, status 0", null: no answer).
     * Only a handshake that asked [SIX] is told about, in [say] (with whether it is a failure): what was asked and answered and, if it failed, that 1.7 is asked from now on.
     *
     * One that did not complete is a failure of 6.0 when Android Auto answered (a refusal, or a step after the answer failed), and, when it was silent, only if
     * 6.0 never completed with the installed Android Auto ([Proof]): with the proof, the silence is the freeze of Android Auto and 6.0 is asked again, and the log says why.
     * A completed one is the proof; a proof of another Android Auto than the installed one is dropped first.
     */
    fun settle(asked: Version, ok: Boolean, answered: String?, say: (line: String, failed: Boolean) -> Unit) {
        if (asked != SIX) return
        val answer = answered ?: "no answer"
        val installed = proof?.installed()
        val proven = provenNow(installed)
        if (ok) {
            if (installed != null) proof?.prove(installed)
            say("Handshake: asked protocol $asked, answered $answer: the handshake completed", false)
        } else if (answered == null && proven) {
            say(
                "Handshake: asked protocol $asked, answered $answer: the handshake did not complete, but silence is not a refusal: " +
                    "protocol $asked completed before with this Android Auto (version code $installed), which Android freezes between sessions; " +
                    "$asked is asked again",
                false,
            )
        } else {
            sixFailed = true
            val why = if (answered == null) "; no answer, and $asked never completed with this Android Auto" else ""
            say(
                "Handshake: asked protocol $asked, answered $answer: the handshake did not complete$why; " +
                    "protocol 1.${Messages.MINOR_NEWER} is asked from now on, until the app is closed",
                true,
            )
        }
    }

    /** Whether 6.0 has completed with the Android Auto of version code [installed]; a proof of another one is taken away (an updated Android Auto starts again). */
    private fun provenNow(installed: Long?): Boolean {
        val kept = proof ?: return false
        val provenOn = kept.provenOn() ?: return false
        if (installed != null && provenOn == installed) return true
        kept.prove(null)
        return false
    }

    internal fun testOnlyReset() {
        sixFailed = false
        proof = null
    }

}
