package com.reau.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.theme.REauMono

// The main screen's foot, in a file of its own since 26/11/2026 (MainScreen.kt had reached detekt's count of functions).

/** The foot: the installed Android Auto, and REau's own version under it (its own function since 26/11/2026, so [MainScreen] stays under the length detekt allows). */
@Composable
internal fun FootLines(state: MainUiState) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val androidAuto = state.androidAutoVersion?.let { stringResource(R.string.aa_version, it) }
        FootLine(androidAuto ?: stringResource(R.string.aa_not_installed), "aa_version")
        // REau's own version under Android Auto's.
        FootLine(stringResource(R.string.reau_version, state.reauVersion), "reau_version")
    }
}

/** One line of the foot: the installed Android Auto, REau's own version. */
@Composable
private fun FootLine(text: String, tag: String) {
    Text(
        text, style = REauMono.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
