package com.reau.app.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.reau.app.data.RideDiagnostics
import com.reau.app.service.AlertChannelState
import com.reau.app.service.ProjectionNotification
import com.reau.app.settings.SettingsStore
import com.reau.app.settings.StartAsks
import com.reau.app.util.MonotonicClock

/**
 * What the main screen's alert card opens ([AlertRoute] decides): Android's window for the notification permission, the page of the app's
 * notifications, or the page of the alert channel. Needs no permission of its own: the pages are ordinary settings pages.
 */
object AlertSettings {
    /** The intent for the channel's page, or for the app's whole notification page when [channel] is false. */
    internal fun intent(context: Context, channel: Boolean): Intent =
        Intent(if (channel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .apply { if (channel) putExtra(Settings.EXTRA_CHANNEL_ID, ProjectionNotification.ALERT_CHANNEL_ID) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun enabled(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(true)

    /** What the card does now, read from Android (and from the mark of the question already asked). */
    fun routeNow(activity: Activity, level: AlertChannelState.Level = AlertChannelState.read(activity)): AlertRoute.Route {
        val granted = activity.checkSelfPermission(StartAsks.NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val store = SettingsStore.open(activity)
        // Granted counts as asked, and is written down: a permission revoked later in Android's settings is then a refusal that shows.
        if (granted && !store.notificationsAsked()) store.notificationsAskedNow()
        val canShow = runCatching { activity.shouldShowRequestPermissionRationale(StartAsks.NOTIFICATIONS) }.getOrDefault(false)
        return AlertRoute.route(level, enabled(activity), granted || store.notificationsAsked(), canShow, Build.VERSION.SDK_INT)
    }

    /** The button of the card: [ask] launches Android's window for the permission; the pages open from here. */
    fun open(activity: Activity, ask: () -> Unit) {
        val route = routeNow(activity)
        RideDiagnostics.log("ui", "the rider tapped the alert card: " + route.logWords())
        when (route) {
            AlertRoute.Route.ASK_PERMISSION -> ask()
            AlertRoute.Route.CHANNEL_PAGE -> openPage(activity, channel = true)
            AlertRoute.Route.APP_PAGE -> openPage(activity, channel = false)
            AlertRoute.Route.NONE -> Unit
        }
    }

    /** The window was answered: [askedAtMs] is when it was launched ([MonotonicClock]); the app's page opens when no window can have been shown. */
    fun answered(activity: Activity, granted: Boolean, askedAtMs: Long) {
        SettingsStore.open(activity).notificationsAskedNow()
        val elapsed = MonotonicClock.nowMs() - askedAtMs
        val page = AlertRoute.openAppPageAfter(granted, enabled(activity), elapsed)
        RideDiagnostics.log("ui", "the notification permission was " + (if (granted) "allowed" else "refused") + (if (page) ": the app's page opened" else ""))
        if (page) openPage(activity, channel = false)
    }

    private fun openPage(context: Context, channel: Boolean) {
        if (runCatching { context.startActivity(intent(context, channel)) }.isFailure && channel) {
            runCatching { context.startActivity(intent(context, channel = false)) }
        }
    }

    private fun AlertRoute.Route.logWords() = when (this) {
        AlertRoute.Route.ASK_PERMISSION -> "asks for the notification permission"
        AlertRoute.Route.CHANNEL_PAGE -> "opens the settings of the alert channel"
        AlertRoute.Route.APP_PAGE -> "opens the settings of the app's notifications"
        AlertRoute.Route.NONE -> "nothing to open (the card should not have shown)"
    }
}
