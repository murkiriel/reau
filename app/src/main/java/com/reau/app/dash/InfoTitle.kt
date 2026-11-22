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
 * The title turns between its base (the street while guiding, "REau" otherwise) and the pages, one at a time: base, a page, base, the next
 * page. Which pages, and in what order of urgency, is the ring's own rule ([InfoRingRotation]: the danger pages alone when there are any, the
 * most urgent one alone with the carousel off).
 *
 * With no route the base is "REau" and each side stays for [TitleSource.periodMs], the ring's time as the rider set it. While guiding the
 * street is what the rider needs, so it stays much longer, whatever the ring's time: [ROUTE_STREET_MS] of street, then [ROUTE_PAGE_MS] of a
 * page, then the street again (with the carousel off, the one page enters the same way). Two more rules, guiding only:
 *  - nearer than [NEAR_METERS] to the next maneuver the title is the street only, whatever the pages are, danger ones included: a rider about
 *    to turn reads the street. At exactly [NEAR_METERS] the pages are back (the distance is "less than", so 499 m is the street only and 500 m
 *    turns);
 *  - going from near to far again (the maneuver was passed, and the next one is already close), the street holds for [AFTER_MANEUVER_HOLD_MS]
 *    before the pages may come back, and the turns start over from the street ([ROUTE_STREET_MS] again): no quick swaps of 4 to 7 s.
 * The echo of a joystick tap ([ActionEcho]) is not decided here: [DashSession] puts it in front of all of this, and the base is its own.
 *
 * Off ([enabled] false) [page] says nothing and the title is the base, untouched. The log gets one line when the mode is switched and one when the title
 * goes to the street only near a maneuver and back; never a street name. The clock and the log are parameters so a test moves the first by hand.
 */
class InfoTitle(
    private val nowMs: () -> Long = MonotonicClock::nowMs,
    private val log: (String) -> Unit = {},
) {
    private val rotation = InfoRingRotation()

    // The turns' clock: from the moment the option came on, and again from the moment the set of pages changed or a route began or ended.
    // Kept here, not by when the title was first asked: near a maneuver, or under an echo, the title is not asked and the turns must not
    // slip for it. After a maneuver it is in the future (the end of the hold): until then the turns have not begun, which is the street.
    private var anchorMs = 0L
    private var guiding: Boolean? = null
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
            guiding = null
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
        val route = metersToManeuver != null
        noteRoute(route)
        noteNear(near, route)
        return if (near || !shown) null else pageNow(route)
    }

    /** The page for this moment, or null when it is the base's turn or there is nothing to say. */
    private fun pageNow(route: Boolean): String? {
        val src = runCatching { source?.invoke() }.getOrNull() ?: return null
        val entries = src.entries.filter { it.text.isNotBlank() && it.text.length <= DashCommands.ROAD_MAX }
        val now = nowMs()
        val turn = rotation.turn(entries.map { it.page }, now, src.carousel)
        val kinds = turn?.pages?.map { it.kind }
        // A new set of pages starts again from the base (the street or REau); the first set seen keeps the clock of the option coming on.
        if (seenKinds != null && kinds != seenKinds) anchorMs = maxOf(now, anchorMs)
        seenKinds = kinds
        // While guiding the street is long and the page short, whatever the ring's time; with no route both are the ring's time.
        val baseMs = if (route) ROUTE_STREET_MS else src.periodMs.coerceAtLeast(1L)
        val pageMs = if (route) ROUTE_PAGE_MS else src.periodMs.coerceAtLeast(1L)
        val elapsed = (now - anchorMs).coerceAtLeast(0L)
        // Each round is the base's time then a page's: base, first page, base, second page...
        return turn?.takeIf { elapsed % (baseMs + pageMs) >= baseMs }?.let { t ->
            val kind = t.pages[((elapsed / (baseMs + pageMs)) % t.pages.size).toInt()].kind
            entries.firstOrNull { it.page.kind == kind }?.text
        }
    }

    /** A route beginning or ending changes what the base is and how long it stays: the turns start over from the base. */
    private fun noteRoute(route: Boolean) {
        if (route == guiding) return
        if (guiding != null) anchorMs = nowMs()
        guiding = route
    }

    private fun noteNear(near: Boolean, route: Boolean) {
        if (near == streetOnly) return
        streetOnly = near
        val hold = !near && route
        if (hold) anchorMs = nowMs() + AFTER_MANEUVER_HOLD_MS
        log(
            if (near) "info title: the street only, near the maneuver"
            else if (hold) "info title: past the maneuver, the street holds for 15 s and the pages turn again"
            else "info title: the pages turn again, past the maneuver",
        )
    }

    companion object {
        /** From this distance (m) to the next maneuver the title is the street only; farther than this, or exactly this far, it turns. */
        const val NEAR_METERS = 500

        /** While guiding, how long the street stays before a page (ms), whatever the ring's time. */
        const val ROUTE_STREET_MS = 20_000L

        /** While guiding, how long a page stays (ms), whatever the ring's time. */
        const val ROUTE_PAGE_MS = 5_000L

        /** After passing a maneuver (near to far), how long the street holds before the turns start over from it (ms). */
        const val AFTER_MANEUVER_HOLD_MS = 15_000L
    }
}
