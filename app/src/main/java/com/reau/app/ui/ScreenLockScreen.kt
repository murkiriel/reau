package com.reau.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.service.ScreenLock
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.theme.REauTheme
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The screen of the "Bloquear a tela" mode (0.3.93 test build): the time, "Projetando no painel", a big padlock and "Pare a moto antes de desbloquear", on the theme's dark
 * ground, with no animation but the thumb's own. Every touch is swallowed; only the thumb, dragged past [ScreenLock.UNLOCK_AT] of the track and let go, leaves the mode.
 * The window's brightness goes to the minimum after [ScreenLockMode.DIM_AFTER_MS], and back when this leaves the screen.
 */
@Composable
internal fun ScreenLockScreen(mode: ScreenLockMode) {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var time by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = format.format(Date())
            delay(CLOCK_MS)
        }
    }
    LaunchedEffect(Unit) {
        delay(ScreenLockMode.DIM_AFTER_MS)
        mode.dim(true)
    }
    DisposableEffect(Unit) { onDispose { mode.dim(false) } }
    REauTheme(dark = true) {
        Surface(Modifier.fillMaxSize().swallowTouches(), color = MaterialTheme.colorScheme.background) {
            val ink = MaterialTheme.colorScheme.onBackground
            Column(
                Modifier.fillMaxSize().padding(top = 40.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(time, style = MaterialTheme.typography.displaySmall.copy(fontSize = 64.sp, lineHeight = 68.sp), color = ink)
                    Text(stringResource(R.string.lock_projecting), style = MaterialTheme.typography.titleMedium, color = ink)
                }
                Column(
                    Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MsIcon(R.drawable.ic_ms_lock, 96.dp, MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.lock_stop_first), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
                // Up from the bottom edge (the home gesture) and in from the sides (the back gesture): the track's margin, and its bottom padding, keep the thumb out of both.
                val bottom = maxOf(TRACK_BOTTOM_MIN, WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding() + 24.dp)
                SlideTrack(Modifier.padding(start = TRACK_MARGIN, end = TRACK_MARGIN, bottom = bottom)) { mode.exit(ScreenLock.Exit.SWIPE) }
            }
        }
    }
}

/** A touch anywhere does nothing: it is taken here, so it never reaches the screens under this one. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

/**
 * The track with the round thumb at its left and "Arraste para desbloquear": the rider has to touch the thumb and drag it to the right; let go before
 * [ScreenLock.UNLOCK_AT] of the way and it goes back to the start, past it [onUnlock] is called. A touch that does not start on the thumb drags nothing.
 */
@Composable
private fun SlideTrack(modifier: Modifier, onUnlock: () -> Unit) {
    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB.toPx() }
    val padPx = with(density) { PAD.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val travel = (widthPx - thumbPx - 2 * padPx).coerceAtLeast(0f)
    Box(
        modifier.fillMaxWidth().height(THUMB + PAD * 2).systemGestureExclusion().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(travel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val onThumb = ScreenLock.hitsThumb(down.position.x, down.position.y, padPx + thumbPx / 2, size.height / 2f, thumbPx / 2, padPx)
                    var dx = 0f
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (onThumb) {
                            dx += change.positionChange().x
                            dragPx = dx.coerceIn(0f, travel)
                        }
                    } while (change.pressed)
                    if (onThumb && ScreenLock.unlocked(ScreenLock.progress(dragPx, travel))) onUnlock() else dragPx = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.lock_slide), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.align(Alignment.CenterStart).offset { IntOffset((padPx + dragPx).roundToInt(), 0) }.size(THUMB).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(R.drawable.ic_ms_chevron_right, 28.dp, MaterialTheme.colorScheme.onPrimary)
        }
    }
}

private val THUMB = 56.dp

/** The track's margin from each side of the screen, and the least its bottom padding is: out of the gesture navigation's edges. */
private val TRACK_MARGIN = 32.dp
private val TRACK_BOTTOM_MIN = 72.dp
private val PAD = 4.dp
private const val CLOCK_MS = 15_000L
