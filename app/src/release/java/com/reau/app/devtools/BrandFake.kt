package com.reau.app.devtools

/**
 * The release build's [BrandFake] (the real one is in `src/debug`): the brand is never faked, and nothing is written. BrandFakeAbsenceInReleaseTest holds that
 * (26/11/2026).
 */
object BrandFake {
    fun maker(): String? = null

    fun logWhenActive() = Unit
}
