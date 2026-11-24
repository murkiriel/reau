package com.reau.app.ui

/**
 * How long the "Descer até o fim" button takes to carry the terms to their end: always one second, a steady scroll the eye can see go by,
 * whatever the length of the text and the density of the screen (the owner's decision of 24/11/2026; before it the time followed the distance,
 * from 1 to 2 s). A touch on the text stops it at any time. When there is nothing to roll (the text fits the screen) there is no scroll at all.
 */
object TermsScroll {
    /** The whole roll, from where the text is to its end. */
    const val DURATION_MS = 1_000
}
