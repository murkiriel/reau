package com.reau.app.ui

import com.reau.app.R

/**
 * What the screen says while the dash that had been working is gone: the seconds since it went when they are known,
 * never a negative number (a clock that went back, a drop recorded after the moment asked about says nothing).
 */
object DashLostText {
    private const val MS_PER_SECOND = 1_000L

    /** Whole seconds from [goneAtMs] to [nowMs] on the same clock; null when the drop is not known or lies after [nowMs]. */
    fun seconds(goneAtMs: Long?, nowMs: Long): Long? =
        goneAtMs?.takeIf { it <= nowMs }?.let { (nowMs - it) / MS_PER_SECOND }

    /** The text for the line under the headline: with the seconds, or without when they are not known. */
    fun text(goneAtMs: Long?, nowMs: Long): Pair<Int, Long?> =
        seconds(goneAtMs, nowMs).let { secs -> (if (secs == null) R.string.sub_dash_lost else R.string.sub_dash_lost_for) to secs }
}
