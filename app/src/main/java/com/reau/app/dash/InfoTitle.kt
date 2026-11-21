package com.reau.app.dash

import com.reau.app.dash.protocol.DashCommands
import com.reau.app.util.MonotonicClock
import com.reau.app.weather.InfoRingRotation
import com.reau.app.weather.RingPage

/** A page of the info ring in words, for the title of the dash's gold band ([com.reau.app.weather.InfoRingTitle]). */
class TitleEntry(val page: RingPage, val text: String)

/** What the title can say now: the pages in words, whether they turn ([carousel]) and how long each stays ([periodMs]), as the ring's options say. */
class TitleSource(val entries: List<TitleEntry>, val carousel: Boolean, val periodMs: Long)

/**
 * "Informações no título" (P31 Título): the info ring's pages written in the title of the dash's gold band (the first field of the route card,
 * [DashSession]) instead of drawn as a ring on the video: one or the other, never both.
 *
 * The title turns between its base (the street while guiding, "REau" otherwise) and the pages, one at a time, each for [TitleSource.periodMs]:
 * base, a page, base, the next page. Which pages, and in what order of urgency, is the ring's own rule ([InfoRingRotation]: the danger pages
 * alone when there are any, the most urgent one alone with the carousel off). While guiding, nearer than [NEAR_METERS] to the next
 * maneuver the title is the street only, whatever the pages are, danger ones included: a rider about to turn reads the street. At exactly
 * [NEAR_METERS] the pages are back (the distance is "less than", so 299 m is the street only and 300 m turns). The echo of a joystick tap
 * ([ActionEcho]) is not decided here: [DashSession] puts it in front of all of this, and the base is its own.
 *
 * Off ([enabled] false) [page] says nothing and the title is the base, untouched. The log gets one line when the mode is switched and one when the title
 * goes to the street only near a maneuver and back; never a street name. The clock and the log are parameters so a test moves the first by hand.
 */
class InfoTitle(
    private val nowMs: () -> Long = MonotonicClock::nowMs,
    private val log: (String) -> Unit = {},
) {
    private val rotation = InfoRingRotation()

    // The turns' clock: from the moment the option came on, and again from the moment the set of pages changed. Kept here, not by when
    // the title was first asked: near a maneuver, or under an echo, the title is not asked and the turns must not slip for it.
    private var anchorMs = 0L
    private var seenKinds: List<com.reau.app.weather.RingKind>? = null
    private var streetOnly = false

    /** What the title can say now, asked at each title; null (or no entries) while there is nothing to say. Set by whoever has the weather. */
    @Volatile var source: (() -> TitleSource?)? = null

    /** Whether the title carries the pages (the rider's "Informações no título" with the info ring on); a change is logged. */
    var enabled: Boolean = false
        @Synchronized set(value) {
            if (value == field) return
            field = value
            streetOnly = false
            anchorMs = nowMs()
            seenKinds = null
            log("info title ${if (value) "on: the info ring's pages are written in the title and the ring is off the video" else "off: the ring is back"}")
        }

    /**
     * The page the title says now, or null when it is the base's turn (the street, or "REau": the caller's own title). [metersToManeuver] is the
     * distance to the next maneuver while guiding, null with no route. [shown] is the rider's joystick switch for the info (the ring's button):
     * hidden, the pages are away. Off, always null: the title is exactly what it always was.
     */
    @Synchronized
    fun page(metersToManeuver: Int?, shown: Boolean = true): String? {
        if (!enabled) return null
        val near = metersToManeuver != null && metersToManeuver < NEAR_METERS
        noteNear(near)
        return if (near || !shown) null else pageNow()
    }

    /** The page for this moment, or null when it is the base's turn or there is nothing to say. */
    private fun pageNow(): String? {
        val src = runCatching { source?.invoke() }.getOrNull() ?: return null
        val entries = src.entries.filter { it.text.isNotBlank() && it.text.length <= DashCommands.ROAD_MAX }
        val now = nowMs()
        val turn = rotation.turn(entries.map { it.page }, now, src.carousel)
        val kinds = turn?.pages?.map { it.kind }
        // A new set of pages starts again from the base (the street or REau); the first set seen keeps the clock of the option coming on.
        if (seenKinds != null && kinds != seenKinds) anchorMs = now
        seenKinds = kinds
        val slot = (now - anchorMs).coerceAtLeast(0L) / src.periodMs.coerceAtLeast(1L)
        // Even slots are the base's, odd ones the pages': base, first page, base, second page...
        return turn?.takeIf { slot % 2 != 0L }?.let { t ->
            val kind = t.pages[((slot / 2) % t.pages.size).toInt()].kind
            entries.firstOrNull { it.page.kind == kind }?.text
        }
    }

    private fun noteNear(near: Boolean) {
        if (near == streetOnly) return
        streetOnly = near
        log(if (near) "info title: the street only, near the maneuver" else "info title: the pages turn again, past the maneuver")
    }

    companion object {
        /** From this distance (m) to the next maneuver the title is the street only; farther than this, or exactly this far, it turns. */
        const val NEAR_METERS = 300
    }
}
