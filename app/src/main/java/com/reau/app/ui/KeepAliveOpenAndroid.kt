package com.reau.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.reau.app.R
import com.reau.app.data.RideDiagnostics

/**
 * The Android side of [KeepAliveOpen]: the intent of each [KeepAliveOpen.Target] and the button's action (26/11/2026). Kept apart so the chain itself has no
 * Android in it.
 */
object KeepAliveOpenAndroid {
    /** The intent that starts [target] for this app; an activity of another app is started inside the task REau is in, so Back returns to REau. */
    internal fun intentFor(context: Context, target: KeepAliveOpen.Target): Intent = when (target) {
        is KeepAliveOpen.Target.Component -> Intent().setComponent(ComponentName(target.packageName, target.className)).also { intent ->
            target.packageExtra?.let { intent.putExtra(it, context.packageName) }
        }
        KeepAliveOpen.Target.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }

    /**
     * The button: walks the chain of [brand] ([KeepAliveOpen.open]), writes each step in the ride log, and, when nothing opens, tells the rider in a Toast, which is
     * the one thing a failed button must not leave out (the owner's rule: never fail in silence).
     */
    fun openFor(context: Context, brand: KeepAliveGuide.Brand) {
        val way = KeepAliveOpen.open(brand, { target -> context.startActivity(intentFor(context, target)) }, { runCatching { RideDiagnostics.log("ui", it) } })
        if (way == null) Toast.makeText(context, R.string.keepalive_open_failed, Toast.LENGTH_LONG).show()
    }
}
