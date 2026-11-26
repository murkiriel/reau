package com.reau.app.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.reau.app.R
import com.reau.app.ui.theme.REauTheme
import java.util.Date
import kotlinx.coroutines.delay

/**
 * "Mostrar o REau sobre o bloqueio" (the test build's step 2 of "Tela acesa com o celular bloqueado"): a light screen with the clock, "Projetando no painel" and
 * "Deslize para cima para desbloquear", shown over the lock screen and turning the screen on ([ScreenKeeper][com.reau.app.service.ScreenKeeper] starts it
 * when it lights the screen). Its window goes to the dimmest brightness after [DIM_AFTER_MS] with no touch; a touch asks Android to dismiss the lock screen
 * and the activity closes. It has no permission of its own: Android may refuse to start it from the background, and the ride log says whether it did.
 */
class ScreenKeepActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val dim = Runnable { setBrightness(DIMMEST) }

    // The unlock by any other way (a fingerprint on the lock screen): this screen would stay over the home screen, so it goes.
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.reau.app.settings.AppLanguages.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { REauTheme(dark = true) { LockScreen() } }
    }

    override fun onResume() {
        super.onResume()
        current = java.lang.ref.WeakReference(this)
        resumed = true
        resumes++
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        main.removeCallbacks(dim)
        main.postDelayed(dim, DIM_AFTER_MS)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(dim)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // A touch anywhere (a tap or the start of a swipe) asks for the unlock.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) unlock()
        return true
    }

    private fun unlock() {
        main.removeCallbacks(dim)
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
                override fun onDismissError() = finish()
            },
        )
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.also { it.screenBrightness = value }
    }

    companion object {
        /** With no touch for this long the window's brightness goes to the minimum. */
        internal const val DIM_AFTER_MS = 5_000L
        private const val DIMMEST = 0.01f

        /** How many times it has come to the front and whether it is there now: [ScreenKeeper][com.reau.app.service.ScreenKeeper] reads them to log "shown" or "blocked". */
        @Volatile var resumes = 0
            private set
        @Volatile var resumed = false
            private set
        private var current: java.lang.ref.WeakReference<ScreenKeepActivity>? = null

        /** Closes the activity if it is open (the function went off or the projection stopped); on the main thread. */
        fun close() {
            current?.get()?.finish()
            current = null
        }
    }
}

/** The screen: the time, the state of the projection and the way to unlock, in the app's own colours. */
@Composable
private fun LockScreen() {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var time by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = format.format(Date())
            delay(CLOCK_MS)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

private const val CLOCK_MS = 15_000L
