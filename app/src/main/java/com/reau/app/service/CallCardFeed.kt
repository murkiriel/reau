package com.reau.app.service

import com.reau.app.aa.AaCaller
import com.reau.app.dash.CallCards
import com.reau.app.dash.CallNotice
import com.reau.app.dash.CallWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * REau's own call card ([CallCards]) between Android Auto's word of the calls and the compositor. The [tracker] follows the call whatever the switch says, so a switch
 * turned on in the middle of a call shows the card with the time already run; [on] only decides whether the card is given to [show] (the compositor draws it) and whether
 * the phase goes to the log. The card is given again at each change of the call and, while a call goes on or has just ended, at each next change by itself
 * ([CallCards.Tracker.nextTickMs]: each whole second, the end of the grey card). [words] is asked at each drawing, so the language is the one of the moment; [log]
 * gets the ride log's lines (no name, no number).
 */
internal class CallCardFeed(
    private val scope: CoroutineScope,
    private val show: (CallNotice?) -> Unit,
    private val words: () -> CallWords,
    private val log: (String) -> Unit,
    private val tracker: CallCards.Tracker = CallCards.Tracker(),
) {
    @Volatile var on = false
        private set
    private var tick: Job? = null

    /** Android Auto's word of the calls changed ([caller]: the one that rings or goes on, null for none; [privateName] stands for a call with no name or number). */
    fun heard(caller: AaCaller?, privateName: String) {
        val changed = if (caller == null) tracker.onNoCall() else tracker.onCall(caller.name.ifEmpty { privateName }, caller.inCall)
        if (changed && on) tracker.current()?.let { log(CallCards.logLine(it)) }
        refresh()
    }

    /** The switch: false when it was already so; turned on in the middle of a call the card shows at once and says its phase, turned off it is taken away. */
    fun switch(wanted: Boolean): Boolean {
        if (on == wanted) return false
        on = wanted
        if (wanted) tracker.current()?.let { log(CallCards.logLine(it)) }
        refresh()
        return true
    }

    /** Gives [show] what the card says now (nothing with the switch off) and waits for the next change of it by itself. */
    @Synchronized
    private fun refresh() {
        tick?.cancel()
        tick = null
        val card = tracker.current()?.takeIf { on }
        show(card?.let { CallCards.notice(it, words()) })
        val wait = if (card != null) tracker.nextTickMs() else null
        if (wait != null) {
            tick = scope.launch {
                delay(wait)
                refresh()
            }
        }
    }
}
