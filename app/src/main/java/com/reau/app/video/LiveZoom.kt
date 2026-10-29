package com.reau.app.video

/** Where the joystick's zoom is kept between projections (the phone's preferences; none in a test). */
interface ZoomMemory {
    var percent: Int
}

/**
 * The map's zoom from the dash's joystick: [percent] from 100 (the picture as the fit puts it) to [MAX] in steps of [STEP], the
 * bottom edge of the picture kept ([fromBottom]). It sits on top of the fit and the zoom of the settings, for the navigation apps only
 * ([appliesTo]): the app menu and the other apps (Spotify, the phone...) are laid out for the whole picture and a zoomed one cuts them.
 * Each change is kept in [memory] and the next projection starts from it (it starts at 100 with none).
 */
class LiveZoom(private val memory: ZoomMemory? = null) {
    @Volatile
    var percent: Int = snap(runCatching { memory?.percent }.getOrNull())
        private set

    // The last step that changed the zoom (0: none, or already taken back).
    private var lastChange = 0

    /** One step nearer ([direction] > 0) or farther (< 0); false when it was already at the end and nothing changed. */
    @Synchronized
    fun step(direction: Int): Boolean {
        val next = (percent + STEP * direction.coerceIn(-1, 1)).coerceIn(MIN, MAX)
        val changed = next != percent
        lastChange = next - percent
        percent = next
        if (changed) keep()
        return changed
    }

    /** Takes back the last step if it changed the zoom; false when it changed nothing (the zoom was at an end) or was already taken back. */
    @Synchronized
    fun undoLast(): Boolean {
        if (lastChange == 0) return false
        percent = (percent - lastChange).coerceIn(MIN, MAX)
        lastChange = 0
        keep()
        return true
    }

    /** The zoom now, kept for the next projection; a memory that fails costs only that. */
    private fun keep() {
        runCatching { memory?.percent = percent }
    }

    fun apply(mapping: FrameFit.Mapping): FrameFit.Mapping = fromBottom(mapping, percent)

    /** [mapping] as it is shown while Android Auto is on [screen]: zoomed on the navigation, as the fit made it on any other screen. */
    fun applyFor(screen: com.reau.app.aa.AaScreen, mapping: FrameFit.Mapping): FrameFit.Mapping =
        if (appliesTo(screen)) apply(mapping) else mapping

    companion object {
        const val MIN = 100
        const val MAX = 300
        const val STEP = 25

        /** The joystick's zoom is for the map: Waze and Google Maps. The menu, the other apps and a screen not known yet keep the fit. */
        fun appliesTo(screen: com.reau.app.aa.AaScreen): Boolean = screen == com.reau.app.aa.AaScreen.NAV

        /** A zoom kept (or damaged) taken to the nearest step in range; 100 when there is none. */
        fun snap(kept: Int?): Int {
            val value = kept ?: return MIN
            return ((value.coerceIn(MIN, MAX) - MIN + STEP / 2) / STEP * STEP + MIN).coerceIn(MIN, MAX)
        }

        /**
         * [mapping] nearer by [percent] (100: as it is, never farther) with the bottom edge of its source kept: the source shrinks to
         * [percent] of its size, keeps its middle sideways and its bottom edge, and drops the top; the destination, the shape and the
         * cover stay.
         */
        fun fromBottom(mapping: FrameFit.Mapping, percent: Int): FrameFit.Mapping {
            val z = percent.coerceAtLeast(MIN)
            if (z == MIN) return mapping
            val s = mapping.source
            val w = (s.w * 100 / z).coerceAtLeast(1)
            val h = Math.round(w * s.h.toDouble() / s.w).toInt().coerceAtLeast(1)
            return mapping.copy(source = RectPx(s.x + (s.w - w) / 2, s.y + s.h - h, w, h))
        }
    }
}
