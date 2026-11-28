package com.reau.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What the rider says he did on a phone whose maker closes apps (26/11/2026, A3 of the Xiaomi and realme plan): the ticks of the "Manter o REau vivo" page. It is
 * the rider's word and nothing REau read, so the page says it cannot check, and the log line says "rider says". The line is written at the start of every
 * projection and when a tick changes, so the log of a ride that was closed carries what the rider had told about the phone's settings.
 */
object KeepAliveChecklist {
    /** The things the rider may tick; [key] is the word in the log line. Which of them a phone's family offers is [com.reau.app.ui.KeepAliveGuide.items]. */
    enum class Item(val key: String) { AUTOSTART("autostart"), BACKGROUND("background"), LOCK("lock"), BATTERY("battery") }

    /** What is known of one tick: ticked, ticked and then unticked, or never touched (the log says "?"). */
    enum class Answer(val word: String) { YES("yes"), NO("no"), UNKNOWN("?") }

    /** The ride log line, every item always in the same order so a reader can compare two rides: `keepalive: rider says autostart=yes background=no lock=yes battery=?`. */
    fun line(answers: Map<Item, Answer>): String =
        "keepalive: rider says " + Item.entries.joinToString(" ") { "${it.key}=${(answers[it] ?: Answer.UNKNOWN).word}" }

    /** The answers kept on the phone. */
    fun answers(context: Context): Map<Item, Answer> = Book.open(context).answers()

    /** The rider ticked or unticked [item]: kept, and the line written again (it is the change the log needs to show). */
    fun answer(context: Context, item: Item, checked: Boolean) {
        val book = Book.open(context)
        book.set(item, checked)
        runCatching { RideDiagnostics.log("ui", line(book.answers())) }
    }

    /**
     * The line at the start of a projection, only when the phone's family has a notice of its own ([eligible]: any other maker writes nothing, as before). With
     * nothing ticked it says so with the question marks, which is also worth knowing.
     */
    fun logAtStart(context: Context, eligible: Boolean) {
        if (!eligible) return
        // A debug build with the brand faked says so first (26/11/2026), so the line of the ticks below is never taken for a real Xiaomi's; nothing in a release.
        com.reau.app.devtools.BrandFake.logWhenActive()
        RideDiagnostics.log("settings", line(answers(context)))
    }

    /** What is kept: one number for each item the rider touched (1 ticked, 0 unticked); an item never touched is not there. */
    internal class Book(private val prefs: SharedPreferences) {
        fun answers(): Map<Item, Answer> =
            Item.entries.associateWith { item ->
                when (prefs.getInt(item.key, NOT_TOUCHED)) {
                    1 -> Answer.YES
                    0 -> Answer.NO
                    else -> Answer.UNKNOWN
                }
            }

        fun set(item: Item, checked: Boolean) {
            prefs.edit { putInt(item.key, if (checked) 1 else 0) }
        }

        companion object {
            private const val NAME = "reau_keepalive_checklist"
            private const val NOT_TOUCHED = -1

            fun open(context: Context) = Book((context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE))
        }
    }
}
