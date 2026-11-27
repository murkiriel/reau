package com.reau.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * What to do when the rider touches a window of its own: the activity's `onUserInteraction` never hears a dialog or a sheet (they are other windows), so the idle count
 * ([IdleLockWatch]) would run out in the middle of one. MainActivity provides [IdleLockWatch.touched]; the default does nothing (previews, tests).
 */
val LocalUserTouch = compositionLocalOf<() -> Unit> { {} }

/**
 * Put on the card of every dialog and sheet: each finger that goes down on it tells [LocalUserTouch]. It is read on the way down (the initial pass) and consumes nothing, so the
 * buttons under it work as before. A touch on the scrim, outside the card, is not counted (it only closes the dialog).
 */
@Composable
fun Modifier.countsAsTouch(): Modifier {
    val touch by rememberUpdatedState(LocalUserTouch.current)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch()
        }
    }
}
