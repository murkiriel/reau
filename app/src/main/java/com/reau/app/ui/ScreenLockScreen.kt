package com.reau.app.ui

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reau.app.R
import com.reau.app.data.PhoneNow
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionService
import com.reau.app.service.ScreenLock
import com.reau.app.ui.components.ComponentLogic
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.brandTitle
import com.reau.app.ui.theme.JetBrainsMono
import com.reau.app.ui.theme.Marfim
import com.reau.app.ui.theme.Ouro
import com.reau.app.ui.theme.OuroSuave
import com.reau.app.ui.theme.REauTheme
import com.reau.app.ui.theme.StatusSucesso
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The ground of the lock screen: ABSOLUTE black, `#000000`. A conscious exception to the Aurum rule of never using pure black (Grafite is the dark ground everywhere
 * else): on an AMOLED screen a black pixel is a pixel switched off, and this screen stays lit for as long as the ride lasts, so every lit pixel counts for the battery
 * and the heat. Everything drawn on it is dim: ivory at 70 % or less, soft gold at 60 % or less, no pure white and no large lit area.
 */
internal val LockBlack = Color(0xFF000000)
private val LockClock = Marfim.copy(alpha = 0.70f)
private val LockLabel = OuroSuave.copy(alpha = 0.60f)
private val LockValue = Marfim.copy(alpha = 0.55f)
private val LockDetail = Marfim.copy(alpha = 0.40f)
private val LockOutline = OuroSuave.copy(alpha = 0.15f)

/**
 * The screen of the "Bloquear a tela" mode (0.3.95, in the style of the 0.4.0 prototype's lock screen): a small "REau", the time and the date, the seal "Projetando no
 * painel", a card of three lines (Painel, Projeção, Bateria) with an outline and no fill, the warning "Pare a moto antes de desbloquear" and, at the bottom, the track with the
 * round thumb. Absolute black ground ([LockBlack]), dim texts, no animation but the thumb's own. Every touch is swallowed; only the thumb, dragged past [ScreenLock.UNLOCK_AT] of
 * the track and let go, leaves the mode. The window's brightness goes to the minimum after [ScreenLockMode.DIM_AFTER_MS] ([ScreenLock.BrightnessRule]): a touch on the thumb
 * (and only there) brings it back at once, and it goes down again 5 s after the last touch on the thumb. It comes back to normal when this leaves the screen.
 */
@Composable
internal fun ScreenLockScreen(mode: ScreenLockMode) {
    val rule = remember { ScreenLock.BrightnessRule(ScreenLockMode.DIM_AFTER_MS, SystemClock.elapsedRealtime()) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches) {
        mode.dim(false)
        delay(rule.msUntilDim(SystemClock.elapsedRealtime()))
        mode.dim(true)
    }
    DisposableEffect(Unit) { onDispose { mode.dim(false) } }
    REauTheme(dark = true) {
        Surface(Modifier.fillMaxSize().swallowTouches(), color = LockBlack) {
            Column(
                Modifier.fillMaxSize().padding(top = 40.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LockHeader()
                Column(Modifier.padding(horizontal = TRACK_MARGIN), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LockCard()
                    Text(stringResource(R.string.lock_stop_first), style = MaterialTheme.typography.bodyMedium, color = LockDetail, textAlign = TextAlign.Center)
                }
                // Up from the bottom edge (the home gesture) and in from the sides (the back gesture): the track's margin, and its bottom padding, keep the thumb out of both.
                val bottom = maxOf(TRACK_BOTTOM_MIN, WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding() + 24.dp)
                SlideTrack(
                    Modifier.padding(start = TRACK_MARGIN, end = TRACK_MARGIN, bottom = bottom),
                    onTouch = { onThumb ->
                        if (rule.touch(SystemClock.elapsedRealtime(), onThumb)) touches++
                    },
                ) { mode.exit(ScreenLock.Exit.SWIPE) }
            }
        }
    }
}

/** The small "REau", the time, the date and the seal. */
@Composable
private fun LockHeader() {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val format = remember(locale) { DateFormat.getTimeFormat(context) }
    val dateFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(CLOCK_MS)
        }
    }
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            brandTitle(stringResource(R.string.app_name), Ouro.copy(alpha = 0.7f)), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = LockClock,
        )
        Text(format.format(now), style = MaterialTheme.typography.displaySmall.copy(fontSize = 64.sp, lineHeight = 68.sp), color = LockClock)
        Text(dateFormat.format(now), style = MaterialTheme.typography.bodyMedium, color = LockLabel)
        Seal()
    }
}

/** "Projetando no painel", with a green dot drawn as an outline, not filled. */
@Composable
private fun Seal() {
    val green = StatusSucesso.copy(alpha = 0.55f)
    Row(
        Modifier.border(1.dp, green, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).border(1.5.dp, green, CircleShape))
        Text(stringResource(R.string.lock_projecting), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = LockValue)
    }
}

/**
 * The card of three lines, an outline and no fill: Painel (connected or not), Projeção (the time since it was switched on, minutes and seconds) and Bateria (the
 * percent and the temperature of the last device look). What the app does not have shows "-". Only this card is redrawn at each second.
 */
@Composable
private fun LockCard() {
    val projection by ProjectionService.projection.collectAsStateWithLifecycle()
    val startedAt by ProjectionService.startedAt.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var battery by remember { mutableStateOf(ScreenLock.batteryText(PhoneNow.batteryPctNow(), PhoneNow.batteryTempNow())) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            battery = ScreenLock.batteryText(PhoneNow.batteryPctNow(), PhoneNow.batteryTempNow())
            delay(TICK_MS)
        }
    }
    val rows = listOf(
        stringResource(R.string.lock_card_panel) to panelText(projection),
        stringResource(R.string.lock_card_projection) to (ComponentLogic.elapsedSince(startedAt != null, startedAt, tick) ?: "-"),
        stringResource(R.string.lock_card_battery) to battery,
    )
    Column(Modifier.fillMaxWidth().border(1.dp, LockOutline, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider(thickness = 1.dp, color = LockOutline)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, fontSize = 10.5.sp, letterSpacing = 1.5.sp), color = LockLabel)
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp), color = LockValue)
            }
        }
    }
}

/** The dash's side of the card: the link's own words ("Conectado" ...); the pace is not known here, so it is not said. */
@Composable
private fun panelText(p: Projection): String = stringResource(ScreenText.dash(p))

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
private fun SlideTrack(modifier: Modifier, onTouch: (onThumb: Boolean) -> Unit, onUnlock: () -> Unit) {
    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB.toPx() }
    val padPx = with(density) { PAD.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val travel = (widthPx - thumbPx - 2 * padPx).coerceAtLeast(0f)
    Box(
        modifier.fillMaxWidth().height(THUMB + PAD * 2).systemGestureExclusion().clip(CircleShape)
            .border(1.dp, LockOutline, CircleShape).onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(travel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val onThumb = ScreenLock.hitsThumb(down.position.x, down.position.y, padPx + thumbPx / 2, size.height / 2f, thumbPx / 2, padPx)
                    onTouch(onThumb)
                    var dx = 0f
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        // The move is read before the change is consumed: positionChange() of a consumed change is zero, which left the thumb still
                        // (0.3.93 on the owner's phone, 26/11/2026). The ignore-consumed reading also stands if something above consumed it first.
                        val step = change.positionChangeIgnoreConsumed().x
                        change.consume()
                        if (onThumb) {
                            dx += step
                            dragPx = dx.coerceIn(0f, travel)
                        }
                    } while (change.pressed)
                    if (onThumb && ScreenLock.unlocked(ScreenLock.progress(dragPx, travel))) onUnlock() else dragPx = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.lock_slide), style = MaterialTheme.typography.bodyMedium, color = LockDetail)
        Box(
            Modifier.align(Alignment.CenterStart).offset { IntOffset((padPx + dragPx).roundToInt(), 0) }.size(THUMB).clip(CircleShape)
                .background(LockLabel),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(R.drawable.ic_ms_chevron_right, 28.dp, LockBlack)
        }
    }
}

private val THUMB = 56.dp

/** The track's margin from each side of the screen, and the least its bottom padding is: out of the gesture navigation's edges. */
private val TRACK_MARGIN = 32.dp
private val TRACK_BOTTOM_MIN = 72.dp
private val PAD = 4.dp
private const val CLOCK_MS = 15_000L
private const val TICK_MS = 1_000L
