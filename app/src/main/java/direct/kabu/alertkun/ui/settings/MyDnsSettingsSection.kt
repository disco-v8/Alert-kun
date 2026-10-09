package direct.kabu.alertkun.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.ui.common.SavedPasswordField
import direct.kabu.alertkun.push.GatewayRegistrationKind

@Composable
fun MyDnsSettingsSection(viewModel: MyDnsSettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.clearPassword() } }
    MyDnsSettingsContent(state, viewModel::editMasterId, viewModel::editPassword, viewModel::save)
}

@Composable
internal fun MyDnsSettingsContent(
    state: MyDnsSettingsUiState,
    onMasterIdChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    val enabled = !state.loading && !state.saving
    var passwordVisible by remember(state.password.isEmpty()) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().testTag("mydns_settings")) {
        Text(stringResource(R.string.mydns_settings), Modifier.testTag("mydns_settings_title"))
        Text(stringResource(R.string.mydns_settings_note), Modifier.testTag("mydns_settings_note"), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = state.masterId, onValueChange = onMasterIdChanged, enabled = enabled,
            singleLine = true, label = { Text(stringResource(R.string.mydns_master_id)) },
            modifier = Modifier.fillMaxWidth().testTag("mydns_master_id"))
        SavedPasswordField(state.password, onPasswordChanged, passwordVisible, { passwordVisible = !passwordVisible },
            state.passwordSaved, enabled, state.loading, R.string.mydns_master_password, "mydns_master_password", "mydns",
            showSupportingText = false)
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("mydns_save")) {
            Text(stringResource(R.string.mydns_save_settings))
        }
        if (state.saved) Text(stringResource(R.string.mydns_saved), Modifier.testTag("mydns_save_status"))
        if (state.failed) Text(stringResource(if (state.rollbackFailed) R.string.mydns_rollback_failed else R.string.mydns_save_failed),
            Modifier.testTag("mydns_save_status"), color = MaterialTheme.colorScheme.error)
        val gatewayError = when (state.gatewayResult) {
            GatewayRegistrationKind.AUTHENTICATION_ERROR, GatewayRegistrationKind.NOT_CONFIGURED -> R.string.mydns_gateway_auth_failed
            null, GatewayRegistrationKind.SUCCESS, GatewayRegistrationKind.SKIPPED -> null
            else -> R.string.mydns_gateway_unavailable
        }
        if (gatewayError != null) Text(stringResource(gatewayError), Modifier.testTag("mydns_gateway_status"),
            color = MaterialTheme.colorScheme.error)
    }
}
