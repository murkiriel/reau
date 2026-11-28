package com.reau.app.devtools

/** The release build's [ArrowTrial] (the real one is in `src/debug`): always none, so the real arrow is always the one sent. ArrowTrialAbsenceInReleaseTest holds that. */
object ArrowTrial {
    fun byte(): Int? = null

    /** The debug street-name test's name: none. */
    fun street(): String? = null

    /** The debug street-name test's bytes: none, so the street always goes as Latin-1. */
    fun streetBytes(text: String, max: Int): ByteArray? = null
}
