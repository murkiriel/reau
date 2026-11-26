package com.reau.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val exempt = remember { BatteryWatch.read(context).exempt }
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
