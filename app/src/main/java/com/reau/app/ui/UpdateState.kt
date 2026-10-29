package com.reau.app.ui

import com.reau.app.update.ReleaseCheck

/** Where the manual check for a newer release is: nothing is asked until the rider taps, and nothing is downloaded or installed. */
internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Done(val result: ReleaseCheck.Result) : UpdateState
}
