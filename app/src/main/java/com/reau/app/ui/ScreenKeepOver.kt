package com.reau.app.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import com.reau.app.service.ScreenKeepFront
import com.reau.app.service.ScreenKeepPolicy
import com.reau.app.ui.theme.REauTheme
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's own activity's side of "Mostrar o REau sobre o bloqueio" (the 0.3.90 test build), the way the maker's own app does it: nothing is started from the
 * service. While [ScreenKeepFront.armed] the activity has showWhenLocked and turnScreenOn on (so it stays in front of the lock screen and lights the screen when it
 * resumes), and when it is resumed with the phone locked it shows [LockOverScreen] in place of its own screens: the clock, "Projetando no painel" and "Deslize
 * para cima para desbloquear", dimmed to the minimum after [DIM_AFTER_MS] with no touch; a touch asks Android to dismiss the lock screen. If the rider was in
 * another app when the phone locked, this activity is not in front and nothing shows (the service's log says `lockui not-front`). Every call is on the main thread.
 */
internal class LockOverHost(private val activity: ComponentActivity) {
    /** Whether [LockOverScreen] is to be shown now. */
    var over by mutableStateOf(false)
        private set

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            over = false
        }
    }

    /** From the activity's onCreate: follows [ScreenKeepFront.armed] for as long as the activity lives, and hears the unlock by any other way. */
    fun attach() {
        activity.lifecycleScope.launch { ScreenKeepFront.armed.collect { apply(it) } }
        ContextCompat.registerReceiver(activity, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { activity.unregisterReceiver(unlocked) }
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

    private fun locked() = activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** A touch on the screen: Android is asked to dismiss the lock screen (its own PIN or pattern prompt, if there is one). */
    fun unlock() {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            over = false
            return
        }
        keyguard.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    over = false
                }

                override fun onDismissError() {
                    over = false
                }
            },
        )
    }

    /** The window's brightness: [DIMMEST] while the screen of the function has had no touch for [DIM_AFTER_MS], the phone's own otherwise. */
    fun dim(on: Boolean) {
        val window = activity.window
        window.attributes = window.attributes.also { it.screenBrightness = if (on) DIMMEST else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
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
    LaunchedEffect(Unit) {
        delay(LockOverHost.DIM_AFTER_MS)
        host.dim(true)
    }
    DisposableEffect(Unit) { onDispose { host.dim(false) } }
    REauTheme(dark = true) {
        Surface(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    awaitFirstDown(requireUnconsumed = false)
                    host.unlock()
                }
            },
            color = MaterialTheme.colorScheme.background,
        ) {
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
                Text(
                    stringResource(R.string.screen_keep_lock_swipe), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val CLOCK_MS = 15_000L
