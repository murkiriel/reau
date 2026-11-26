package com.reau.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.WarningCard

// The two cards of the main screen about the system closing REau, apart from the others in MainParts.kt (26/11/2026: that file had reached the most functions detekt allows).

/**
 * The card for the system having closed REau during the last ride ([com.reau.app.data.SystemClosedRide]): when, that it is the phone's doing and not the dash's,
 * and the way to the guide of what lowers the chance; "Entendi" dismisses it. Opening the guide dismisses it too: it has said what it had to.
 */
@Composable
internal fun SystemClosedWarning(atMs: Long?, extras: MainExtras) {
    val at = remember(atMs) { atMs?.let { com.reau.app.data.SystemClosedRide.stamp(it, System.currentTimeMillis()) }.orEmpty() }
    WarningCard(
        StatusKind.CONNECTING, R.drawable.ic_ms_error, stringResource(R.string.warn_system_closed_title), stringResource(R.string.warn_system_closed, at),
        CardAction(stringResource(R.string.action_keepalive_guide), "open_keepalive", extras.onOpenKeepAlive),
        CardAction(stringResource(R.string.action_understood), "dismiss_system_closed", extras.onDismissSystemClosed),
        tag = "system_closed_warning",
    )
}

/**
 * The one notice before the first ride on a Xiaomi or realme phone ([com.reau.app.data.KeepAliveNotice], 26/11/2026): the maker may close REau in a ride, with the
 * way to the page that has the maker's steps; "Entendi" answers it too. Opening the page answers it as well, so it is not shown again.
 */
@Composable
internal fun KeepAliveAdviceWarning(extras: MainExtras) {
    WarningCard(
        StatusKind.CONNECTING, R.drawable.ic_ms_error, stringResource(R.string.warn_keepalive_title), stringResource(R.string.warn_keepalive),
        CardAction(stringResource(R.string.action_keepalive_guide), "open_keepalive_advice", extras.onOpenKeepAlive),
        CardAction(stringResource(R.string.action_understood), "dismiss_keepalive_advice", extras.onDismissKeepAliveAdvice),
        tag = "keepalive_advice_warning",
    )
}
