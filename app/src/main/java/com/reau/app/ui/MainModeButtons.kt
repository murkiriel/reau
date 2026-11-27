package com.reau.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ScreenKeepManual
import com.reau.app.ui.components.GhostButton

/**
 * The outlined button under the bridge card: the card mode while stopped, and with the projection on "Bloquear a tela" (outlined, not gold: Desligar is the screen's
 * one main button; it is there whatever the switch "Tela acesa com o celular bloqueado" says).
 */
@Composable
internal fun ColumnScope.ModeButtons(p: Projection, extras: MainExtras) {
    if (p.phase == Phase.STOPPED && !p.stopping) {
        GhostButton(R.drawable.ic_ms_image, stringResource(R.string.action_card), "card", extras.onCard, Modifier.align(Alignment.CenterHorizontally))
    }
    if (ScreenKeepManual.available(p.phase != Phase.STOPPED)) {
        GhostButton(
            R.drawable.ic_ms_lock, stringResource(R.string.action_lock_screen), "lock_screen", extras.onLockScreen,
            Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
