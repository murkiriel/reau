package com.reau.app.aa

/**
 * The lines that say what happens around the server's start walk, so a log can tell "Android Auto never started it" from
 * "it started it later": the phone's state when the walk begins, what the screen showed shortly after the click on
 * "start", and which Android Auto window came to the front while the link was waiting for the server. Only kinds, short
 * class names, times and counts: never a title, a text of Android Auto's screen, a name or an address.
 */
internal object ServerTrail {
    /** What the screen said a moment after "start" was touched, in the words [ServerTexts] already knows. */
    enum class Known(val word: String) { STARTING("starting"), STARTED("started"), NONE("none") }

    /** The most window lines kept for one projection. */
    const val MAX_WINDOWS = 20

    /** The phone's state, in the words of the `device` line (`saver=on`, `charging=yes`, `screen=on`). */
    fun phone(saver: Boolean?, charging: Boolean?, screenOn: Boolean?): String =
        "saver=${onOff(saver)} charging=${yesNo(charging)} screen=${onOff(screenOn)}"

    /** After the click: whether Android Auto is in front, `+sinceMs` after the click, how many nodes were read, and what they said. */
    fun afterClick(inFront: Boolean, sinceMs: Long, nodes: Int, known: Known): String =
        "after the click: AA in front=${if (inFront) "yes" else "no"} at +$sinceMs ms; nodes=$nodes; known=${known.word}"

    /** "started" when the menu now offers "stop", "starting" when the screen says the server is starting, else "none". */
    fun known(texts: ServerTexts, nodeTexts: List<String?>): Known = when {
        nodeTexts.any { texts.isStop(it) } -> Known.STARTED
        nodeTexts.any { texts.isNotificationText(it) } -> Known.STARTING
        else -> Known.NONE
    }

    /** The class's last part (`DefaultSettingsActivity`), or `?` with none: a title is never written. */
    fun shortClass(className: String?): String = className?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "?"

    /** `aa-window: <class> resumed +N ms`. */
    fun window(shortClass: String, sinceMs: Long): String = "aa-window: $shortClass resumed +$sinceMs ms"

    /** At most [MAX_WINDOWS] window lines for one projection: the next one says nothing. */
    class Windows {
        private var count = 0

        /** The line to write, or null once the projection has had [MAX_WINDOWS]. */
        fun next(className: String?, sinceMs: Long): String? {
            if (count >= MAX_WINDOWS) return null
            count++
            return window(shortClass(className), sinceMs)
        }

        fun reset() {
            count = 0
        }
    }

    private fun onOff(value: Boolean?) = when (value) { true -> "on"; false -> "off"; null -> "?" }

    private fun yesNo(value: Boolean?) = when (value) { true -> "yes"; false -> "no"; null -> "?" }
}
