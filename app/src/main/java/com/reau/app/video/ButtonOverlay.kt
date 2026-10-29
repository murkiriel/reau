package com.reau.app.video

import com.reau.app.dash.protocol.DashButtonEvent
import java.util.Locale

/**
 * REau writes the code of the last dash button it was told of on the dash's own video, orange with a black border, behind a switch in the
 * debug settings (the same way [StatsOverlay] writes the FPS and the bitrate, and set the same way). It stays until another button is pressed
 * or the switch is turned off. [x] and [y] are the top-left corner of the text's box and [size] the height of its letters, in pixels of the
 * dash's 526×300 frame ([DashGlass]). No Android in it: tested on the JVM.
 */
data class ButtonOverlay(
    val on: Boolean = false,
    val x: Int = DEFAULT_X,
    val y: Int = DEFAULT_Y,
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        val X = StatsOverlay.X
        val Y = StatsOverlay.Y
        val SIZE = StatsOverlay.SIZE

        // Under the stats text's default place (its three lines end near 110), still inside the round glass.
        const val DEFAULT_X = 170
        const val DEFAULT_Y = 96
        const val DEFAULT_SIZE = 32

        /** What the preview on the "Tela do Android Auto" shows before any button has been pressed. */
        val SAMPLE = ButtonText("0x05", "PlayPause", "media center once")

        /** The last button the dash sent, for the preview too (the stream and the screen are in the one process); null before the first. */
        @Volatile var latest: ButtonText? = null

        /** The text for [event]: its code in hex, its name in REau, and [detail] (what REau did with it: the log line's first words). */
        fun textFor(event: DashButtonEvent, detail: String? = null): ButtonText =
            ButtonText(String.format(Locale.US, "0x%02X", event.code), event::class.simpleName.orEmpty(), detail.orEmpty())
    }
}

/**
 * What is written: the [code] and its [name] on the first line, and under them, smaller, the [detail] when there is one (broken into short
 * lines so the round glass does not cut it, [detailLines]) and the [note] on the maneuver ring when it has changed.
 */
data class ButtonText(val code: String, val name: String, val detail: String = "", val note: String = "") {
    val first: String get() = "$code  $name"

    /** The detail in lines of at most [LINE_CHARS]: first split where it says "->" (what was pressed, what it did), then at the last space or comma that fits. */
    val detailLines: List<String> get() = detail.split(" -> ").flatMap { wrap(it.trim(), LINE_CHARS) }.filter { it.isNotEmpty() }

    companion object {
        const val LINE_CHARS = 26

        private fun wrap(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val cut = text.take(max).lastIndexOfAny(charArrayOf(' ', ',', ';')).takeIf { it > 0 } ?: (max - 1)
            return listOf(text.take(cut + 1).trim()) + wrap(text.drop(cut + 1).trim(), max)
        }

        /** The note for the maneuver ring becoming [shown] or hidden, and why ([cause]: "joystick 0x05", "screen MENU"). */
        fun ringNote(shown: Boolean, cause: String): String = "maneuver ring ${if (shown) "shown" else "hidden"}: $cause"
    }
}
