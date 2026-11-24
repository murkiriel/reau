package com.reau.app.ui

import com.reau.app.service.AlertChannelState.Level

/**
 * What the main screen's alert card does (24/11/2026), decided from what Android says; the decision is pure, [AlertSettings] only asks Android and acts.
 *
 * With the whole app blocked, the page of the alert channel turns nothing on (the owner pressed it fifteen times on a clean install): on Android 13+
 * that is the notification permission, and the card asks for it with Android's own window; when Android will not show the window any more (denied for
 * good), it opens the page of the app's notifications. The channel's page is only for a channel that is blocked or muted inside an app that is allowed.
 * And on a clean install the card does not show before REau has asked for the permission (Ligar asks it): no one has refused anything yet.
 */
object AlertRoute {
    enum class Route {
        /** No card: the alerts would be heard, or REau has not asked for the permission yet. */
        NONE,

        /** The card's button asks for the notification permission (Android's window "Permitir notificações?"). */
        ASK_PERMISSION,

        /** The page of the app's notifications, all of them. */
        APP_PAGE,

        /** The page of the "Avisos do painel" channel only. */
        CHANNEL_PAGE,
    }

    /** The first Android with the notification permission asked at run time (13). */
    private const val TIRAMISU = 33

    /**
     * A person cannot read and answer Android's window in less than this: an answer that comes back sooner than it was never shown (Android
     * answers "denied" by itself once the rider has refused for good).
     */
    internal const val WINDOW_MIN_MS = 400L

    /**
     * [level]: what [com.reau.app.service.AlertChannelState] read. [appEnabled]: Android lets the app notify at all. [asked]: REau already asked for
     * the permission, or it is granted ([com.reau.app.settings.SettingsStore.notificationsAsked]). [canShowWindow]: Android would still show its
     * window (`shouldShowRequestPermissionRationale`, true after one refusal). [sdk]: the Android version.
     */
    fun route(level: Level, appEnabled: Boolean, asked: Boolean, canShowWindow: Boolean, sdk: Int): Route = when {
        level == Level.LOUD -> Route.NONE
        appEnabled -> Route.CHANNEL_PAGE
        sdk < TIRAMISU -> Route.APP_PAGE
        !asked -> Route.NONE
        canShowWindow -> Route.ASK_PERMISSION
        else -> Route.APP_PAGE
    }

    /**
     * After the window: the page of the app's notifications opens when the app is still blocked and the answer was either "allowed" (a switch
     * that is off in Android's own settings, not the permission) or too quick to have been a person's ([WINDOW_MIN_MS]: no window was shown).
     */
    fun openAppPageAfter(granted: Boolean, enabledNow: Boolean, elapsedMs: Long): Boolean = !enabledNow && (granted || elapsedMs < WINDOW_MIN_MS)
}
