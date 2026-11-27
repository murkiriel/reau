package com.reau.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.reau.app.R
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ScreenLock
import com.reau.app.ui.components.GhostButton
import com.reau.app.ui.components.GoldBigButton

/**
 * Under the bridge card. Stopped: the card mode's outlined "Mandar o wallpaper". With the projection on: "Bloquear a tela", the gold button of the screen (Desligar is the
 * outlined one then), the same component as Ligar. The wallpaper is NOT offered with the projection on: the card mode replaces Android Auto, and a start of it while a projection
 * runs is ignored by the machine ([com.reau.app.service.ProjectionMachine]: a start that finds the phase not stopped changes nothing), so the button would do nothing.
 */
@Composable
internal fun ColumnScope.ModeButtons(p: Projection, extras: MainExtras) {
    if (p.phase == Phase.STOPPED && !p.stopping) {
        GhostButton(R.drawable.ic_ms_image, stringResource(R.string.action_card), "card", extras.onCard, Modifier.align(Alignment.CenterHorizontally))
    }
    if (ScreenLock.available(p.phase != Phase.STOPPED)) {
        GoldBigButton(
            R.drawable.ic_ms_lock, stringResource(R.string.action_lock_screen), extras.onLockScreen,
            Modifier.fillMaxWidth().height(72.dp).testTag("lock_screen"),
        )
    }
}
