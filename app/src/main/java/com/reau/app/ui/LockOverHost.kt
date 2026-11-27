package com.reau.app.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.reau.app.R
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.LockLog
import com.reau.app.service.ScreenKeepFront
import com.reau.app.service.ScreenKeepPolicy
import com.reau.app.service.ScreenLockLog
import com.reau.app.service.ScreenWatch
import com.reau.app.ui.theme.REauTheme
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's own activity's side of "Mostrar o REau sobre o bloqueio" (the 0.3.90 test build), the way the maker's own app does it: nothing is started from the
 * service. While [ScreenKeepFront.armed] the activity has showWhenLocked and turnScreenOn on (so it stays in front of the lock screen and lights the screen when it
 * resumes), and when it is resumed with the phone locked it shows [LockOverScreen] in place of its own screens: the clock, "Projetando no painel" and "Toque
 * para desbloquear", dimmed to the minimum after [DIM_AFTER_MS] with no touch; a touch asks Android to dismiss the lock screen. If the rider was in
 * another app when the phone locked, this activity is not in front and nothing shows (the service's log says `lockui not-front`). Every call is on the main thread.
 */
internal class LockOverHost(private val activity: ComponentActivity) {
    /** Whether [LockOverScreen] is to be shown now, over the lock screen. */
    var over by mutableStateOf(false)
        private set

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            over = false
            // The phone was unlocked (the lock screen state lines take it as the cause of the lock screen going away).
            ScreenWatch.screenEvent(intent.action)
        }
    }

    /** Follows the window's brightness over the lock screen and says each change (`lockui brightness dim reason=idle`). */
    private var dimTrack = ScreenLockLog.DimTrack("lockui brightness")

    /**
     * From the activity's onPostCreate (after its setContent): adds the layer of [LockOverScreen] on top of the activity's screens (it draws and takes touches only while
     * [over]), follows [ScreenKeepFront.armed] for as long as the activity lives, and hears the unlock by any other way.
     */
    fun attach() {
        activity.addContentView(
            ComposeView(activity).apply { setContent { if (over) LockOverScreen(this@LockOverHost) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activity.lifecycleScope.launch { ScreenKeepFront.armed.collect { apply(it) } }
        runCatching { ContextCompat.registerReceiver(activity, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { log(LockLog.warnLine("listening to the unlock", it)) }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { activity.unregisterReceiver(unlocked) }.onFailure { log(LockLog.warnLine("stop listening to the unlock", it)) }
                ScreenKeepFront.resumed = false
            }
        })
    }

    private fun apply(armed: Boolean) {
        activity.setShowWhenLocked(armed)
        activity.setTurnScreenOn(armed)
        if (armed) RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.ARMED_LINE) else over = false
    }

    /** The activity is resumed: it says so to the service, and over the lock screen it shows the screen of the function. */
    fun resumed() {
        ScreenKeepFront.resumed = true
        over = ScreenKeepFront.armed.value && locked()
        if (over) RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.SHOWN_LINE)
    }

    fun paused() {
        ScreenKeepFront.resumed = false
    }

    /** "Apagar a tela nesta viagem": the screen is not lit again until the next projection; the saved setting stays. The screen over the lock screen goes and the phone's timeout takes over. */
    fun pauseByRider() {
        RideDiagnostics.log(ScreenKeepPolicy.TAG, ScreenKeepPolicy.PAUSED_LINE)
        ScreenKeepFront.pauseByRider()
    }

    private fun locked() = activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun log(line: String) = RideDiagnostics.log(ScreenKeepPolicy.TAG, line)

    /**
     * A touch on the screen: Android is asked to dismiss the lock screen (its own PIN or pattern prompt, if there is one). The ask and each way the answer can come (succeeded,
     * cancelled, error) are in the log; a failure to ask at all is a warning, and the screen over the lock screen goes away in each case but the ask itself.
     */
    fun unlock() {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            log(LockLog.unavailableLine("keyguard manager"))
            over = false
            return
        }
        log(LockLog.dismissLine(LockLog.DISMISS_ASKED))
        runCatching {
            keyguard.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        log(LockLog.dismissLine(LockLog.DISMISS_SUCCEEDED))
                        ScreenWatch.dismissed()
                        over = false
                    }

                    override fun onDismissCancelled() {
                        // The rider cancelled the PIN prompt: the screen of the function stays, to be touched again.
                        log(LockLog.dismissLine(LockLog.DISMISS_CANCELLED))
                    }

                    override fun onDismissError() {
                        log(LockLog.dismissLine(LockLog.DISMISS_ERROR))
                        over = false
                    }
                },
            )
        }.onFailure {
            log(LockLog.warnLine("dismiss of the lock screen", it))
            over = false
        }
    }

    /**
     * The window's brightness: [DIMMEST] while the screen of the function has had no touch for [DIM_AFTER_MS], the phone's own otherwise. A change is written, with [reason]
     * (null: `idle` going dim, `exit` coming back; the screen says `enter` or `touch`).
     */
    fun dim(on: Boolean, reason: String? = null) {
        val window = activity.window
        window.attributes = window.attributes.also { it.screenBrightness = if (on) DIMMEST else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        dimTrack.set(on, reason, SystemClock.elapsedRealtime())?.let(::log)
    }

    companion object {
        /** With no touch for this long the window's brightness goes to the minimum. */
        const val DIM_AFTER_MS = 5_000L
        private const val DIMMEST = 0.01f
    }
}

/** The screen over the lock screen: the time, the state of the projection and the way to unlock, in the app's own colours; any touch unlocks. */
@Composable
internal fun LockOverScreen(host: LockOverHost) {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var time by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = format.format(Date())
            delay(CLOCK_MS)
        }
    }
    // A touch brings the brightness back and the count to the minimum begins again: the rider who cancelled Android's PIN prompt is still on this screen.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches) {
        host.dim(false, if (touches == 0) ScreenLockLog.BRIGHT_ENTER else ScreenLockLog.BRIGHT_TOUCH)
        delay(LockOverHost.DIM_AFTER_MS)
        host.dim(true)
    }
    DisposableEffect(Unit) { onDispose { host.dim(false) } }
    REauTheme(dark = true) {
        Surface(Modifier.fillMaxSize().overTouch(host) { touches++ }, color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val ink = MaterialTheme.colorScheme.onBackground
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(time, style = MaterialTheme.typography.displaySmall.copy(fontSize = 64.sp, lineHeight = 68.sp), color = ink)
                    Text(stringResource(R.string.screen_keep_lock_projecting), style = MaterialTheme.typography.titleMedium, color = ink)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.screen_keep_lock_swipe),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    // A discreet button: its touch is its own (it consumes the press), so only a touch elsewhere unlocks.
                    TextButton(onClick = host::pauseByRider) {
                        Text(
                            stringResource(R.string.screen_keep_lock_pause), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Over the lock screen any touch unlocks: every touch (one gesture after the other, not the first only) asks Android to dismiss the lock screen, so a rider who cancelled its
 * PIN prompt can touch again. [onTouch] is told first (the brightness).
 */
private fun Modifier.overTouch(host: LockOverHost, onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        onTouch()
        host.unlock()
    }
}

private const val CLOCK_MS = 15_000L
