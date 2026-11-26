package com.reau.app.aa

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit

/**
 * Why the head unit server is off, when Android Auto was updated: an update of Android Auto turns its developer option "Start head unit server" off, and
 * the rider who switched it on once does not see why it "turned off by itself". REau keeps, on the phone, the update time of Android Auto at the last
 * moment it saw the server on (a dial accepted); with the server off and Android Auto updated after that, the main screen says so.
 */
object AaUpdatedServerOff {
    /**
     * Whether the server being off is the work of an update of Android Auto: the link says it is off ([link]), REau saw it on at some time
     * ([seenOnAt], the update time of Android Auto then; null when never) and Android Auto was updated since ([updatedAt] now; null with none installed).
     * With no value kept (the first time) it is not known, and the answer is no.
     */
    fun decide(link: AaLinkState, seenOnAt: Long?, updatedAt: Long?): Boolean =
        link == AaLinkState.SERVER_OFF && seenOnAt != null && updatedAt != null && updatedAt > seenOnAt

    /** The ride log's line when it is the case; no names, no numbers of the phone. */
    const val LOG_LINE = "Android Auto was updated since the head unit server last worked: the update may have turned it off, the screen says so"

    /** When the installed Android Auto was last installed or updated (the package's `lastUpdateTime`, in epoch ms), or null when there is none. */
    fun androidAutoUpdatedAt(context: Context): Long? = try {
        context.packageManager.getPackageInfo(AndroidAuto.PACKAGE, 0).lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Notes that the server was on now, with Android Auto as updated at [updatedAt]; null (no Android Auto) notes nothing. */
    fun markSeenOn(context: Context, updatedAt: Long? = androidAutoUpdatedAt(context)) {
        if (updatedAt != null) Seen.open(context).seenOn(updatedAt)
    }

    /** Whether the server being off is the update's work, with what is kept on the phone and what Android Auto says now. */
    fun now(context: Context, link: AaLinkState): Boolean =
        decide(link, Seen.open(context).seenOnAt(), androidAutoUpdatedAt(context))

    /** What is kept on the phone: the update time of Android Auto the last time the server was seen on. */
    internal class Seen(private val prefs: SharedPreferences) {
        fun seenOnAt(): Long? = if (prefs.contains(KEY)) prefs.getLong(KEY, 0L) else null

        fun seenOn(updatedAt: Long) {
            if (seenOnAt() != updatedAt) prefs.edit { putLong(KEY, updatedAt) }
        }

        companion object {
            private const val NAME = "reau_aa_server_seen"
            private const val KEY = "aa_updated_at_when_on"

            fun open(context: Context) = Seen((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
        }
    }
}
