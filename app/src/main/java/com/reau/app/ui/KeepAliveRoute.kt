package com.reau.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.reau.app.data.KeepAliveChecklist
import com.reau.app.data.KeepAliveNotice
import com.reau.app.data.RideDiagnostics

/**
 * The "Manter o REau vivo" page with what it needs from the phone (26/11/2026): the battery exemption as Android has it now, the ticks the rider kept, the
 * buttons that open the phone's own screens ([KeepAliveOpenAndroid]), and the answer to the one-time notice when the page opens ([KeepAliveNotice]).
 */
@Composable
internal fun KeepAliveRoute(context: Context, back: () -> Unit) {
    val brand = KeepAliveGuide.current()
    var exempt by remember { mutableStateOf(BatteryWatch.read(context).exempt) }
    // Read again at each return to the screen (26/11/2026): the rider goes to Android's battery screen from here, sets "unrestricted" and comes back, and the page read
    // the exemption once and went on saying "not yet". The activity is the lifecycle owner; the plain observer of the lifecycle library in use has no compose effect for it.
    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BatteryWatch.read(context).exempt
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var answers by remember { mutableStateOf(KeepAliveChecklist.answers(context)) }
    KeepAliveScreen(
        back,
        onOpenAppInfo = {
            RideDiagnostics.log("ui", "the rider opened REau's app info from the keep-alive guide")
            KeepAliveOpenAndroid.openFor(context, KeepAliveGuide.Brand.OTHER)
        },
        brand = brand,
        batteryExempt = exempt,
        answers = answers,
        onAnswer = { item, checked ->
            KeepAliveChecklist.answer(context, item, checked)
            answers = KeepAliveChecklist.answers(context)
        },
        onOpenAutostart = {
            RideDiagnostics.log("ui", "the rider tapped the button to the Autostart screen (brand ${brand.name.lowercase()})")
            KeepAliveOpenAndroid.openFor(context, brand)
        },
        onSeen = { KeepAliveNotice.answered(context, KeepAliveGuide.hasNotice(brand), "opened the steps") },
    )
}
