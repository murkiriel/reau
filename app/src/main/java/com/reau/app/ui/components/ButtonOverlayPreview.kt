package com.reau.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.reau.app.ui.AaScreenMask
import com.reau.app.video.ButtonOverlay
import com.reau.app.video.ButtonOverlayInk
import com.reau.app.video.DashGlass
import com.reau.app.video.DashMask
import kotlinx.coroutines.delay

/**
 * The last-button text of the dash's video ([ButtonOverlay]) on the "Tela do Android Auto", placed in the dash's 526×300 frame as the
 * mask places that frame on the picture and inked as the video inks it ([ButtonOverlayInk]). It shows the last press ([ButtonOverlay.latest],
 * read twice a second) and, before any, a sample so the place can be set.
 */
@Composable
fun ButtonOverlayPreview(mask: AaScreenMask, button: ButtonOverlay, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(ButtonOverlay.latest ?: ButtonOverlay.SAMPLE) }
    LaunchedEffect(Unit) {
        while (true) {
            text = ButtonOverlay.latest ?: ButtonOverlay.SAMPLE
            delay(REFRESH_MS)
        }
    }
    Canvas(modifier) {
        val m = DashMask.of(mask.mapping, mask.sourceW, mask.sourceH, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        // Screen pixels per frame pixel; the fit can stretch the picture, so one for each way (the letters take the width's).
        val scaleX = (m.frame.right - m.frame.left) / DashGlass.FRAME_W
        val scaleY = (m.frame.bottom - m.frame.top) / DashGlass.FRAME_H
        drawIntoCanvas {
            ButtonOverlayInk.draw(it.nativeCanvas, text, button.size, m.frame.left + button.x * scaleX, m.frame.top + button.y * scaleY, scaleX)
        }
    }
}

private const val REFRESH_MS = 500L
