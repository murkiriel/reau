package com.reau.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The "Tela do Android Auto" turned on its side (28/10/2026): the picture goes full screen, the whole of Android Auto's picture and nothing else
 * (no bar, no fit, no mask), so it can be used with a finger. The picture is drawn by the compositor on the screen's surface as big as fits,
 * centred, on black ([com.reau.app.video.CompositorMath.previewViewport]), and a finger on the black is dropped
 * ([com.reau.app.aa.PhoneTouch.toVideo]): the full screen only gives the surface the whole window.
 */
object AaFullScreen {
    fun isLandscape(orientation: Int): Boolean = orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Full screen on a landscape phone, unless the rider left it with Back (then the screen as before, until it is turned upright). */
    fun on(landscape: Boolean, dismissed: Boolean): Boolean = landscape && !dismissed

    /** What "left with Back" is after the phone turns: kept while on its side, forgotten upright, so the next turn enters again. */
    fun dismissedAfter(landscape: Boolean, dismissed: Boolean): Boolean = dismissed && landscape
}

/** The system bars out of the way while [on], back when it goes off or the screen is left (a swipe from the edge shows them for a moment). */
@Composable
internal fun ImmersiveWhile(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on, view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (on && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (on) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
