package com.reau.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.color
import com.reau.app.ui.theme.REauSpace

/**
 * Asked before the network server is turned on with the projection running or the dash in use (the rule is
 * [com.reau.app.web.WebSwitch.needsConfirmation]): with it on, REau stops using the dash and the picture goes to the browser alone. Cancelar (or
 * a tap outside) leaves the setting as it was; Ligar turns it on. Text and a question only: the server and the search for the dash do not change.
 */
@Composable
internal fun WebOnDialog(onCancel: () -> Unit, onTurnOn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { MsIcon(R.drawable.ic_ms_error, 28.dp, StatusKind.CONNECTING.color()) },
        title = {
            Text(stringResource(R.string.web_on_dialog_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("web_on_title"))
        },
        text = { Text(stringResource(R.string.web_on_dialog_text), modifier = Modifier.testTag("web_on_text")) },
        confirmButton = {
            TextButton(onTurnOn, Modifier.heightIn(min = REauSpace.touch).testTag("web_on_confirm")) { Text(stringResource(R.string.action_start)) }
        },
        dismissButton = {
            TextButton(onCancel, Modifier.heightIn(min = REauSpace.touch).testTag("web_on_cancel")) {
                Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant)
            }
        },
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    )
}
