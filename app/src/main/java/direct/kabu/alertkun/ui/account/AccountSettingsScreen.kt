package direct.kabu.alertkun.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import direct.kabu.alertkun.data.repository.AccountSaveFailure
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.MailProtocol

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountSettingsScreen(onBack: () -> Unit, viewModel: AccountSettingsViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts = state.accounts
    val editor = state.editor
    var passwordVisible by remember(editor.id, editor.password.isEmpty()) { mutableStateOf(false) }
    val setEditor: (AccountEditor) -> Unit = viewModel::edit
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.account_settings)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).testTag("account_form").padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(editor.name, { setEditor(editor.copy(name = it)) }, enabled = !state.busy, label = { Text(stringResource(R.string.account_name)) }, modifier = Modifier.fillMaxWidth().testTag("account_name"))
            Text(stringResource(R.string.account_protocol))
            MailProtocol.entries.forEach { protocol ->
                Row { RadioButton(protocol == editor.protocol, { setEditor(editor.copy(protocol = protocol, port = defaultPort(protocol).toString())) }, enabled = !state.busy); Text(stringResource(protocolLabel(protocol))) }
            }
            OutlinedTextField(editor.host, { setEditor(editor.copy(host = it)) }, enabled = !state.busy, label = { Text(stringResource(R.string.account_host)) }, modifier = Modifier.fillMaxWidth().testTag("account_host"))
            OutlinedTextField(editor.port, { setEditor(editor.copy(port = it)) }, enabled = !state.busy, label = { Text(stringResource(R.string.account_port)) }, modifier = Modifier.fillMaxWidth().testTag("account_port"))
            if (editor.protocol in listOf(MailProtocol.POP3S, MailProtocol.IMAPS)) {
                TlsValidationSelector(editor.tlsValidationMode, !state.busy, { setEditor(editor.copy(tlsValidationMode = it)) })
            }
            OutlinedTextField(editor.username, { setEditor(editor.copy(username = it)) }, enabled = !state.busy, label = { Text(stringResource(R.string.account_username)) }, modifier = Modifier.fillMaxWidth().testTag("account_username"))
            key(editor.id) {
                AccountPasswordField(editor.password, { setEditor(editor.copy(password = it)) }, passwordVisible, { passwordVisible = !passwordVisible }, editor.hasSavedPassword, !state.busy, state.credentialLoading)
            }
            Row { Checkbox(editor.enabled, { setEditor(editor.copy(enabled = it)) }, enabled = !state.busy); Text(stringResource(R.string.enabled)) }
            ServerDeletePolicySelector(editor.serverDeletePolicy, !state.busy) {
                setEditor(editor.copy(serverDeletePolicy = it))
            }
            AccountPrimaryActionContent(state, onTest = viewModel::test, onSave = viewModel::save)
            accounts.forEach { account ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(account.name)
                    TextButton(onClick = { viewModel.select(account) }, enabled = !state.busy, modifier = Modifier.testTag("account_edit_${account.id}")) { Text(stringResource(R.string.account_edit)) }
                    TextButton(onClick = { viewModel.delete(account) }, enabled = !state.busy, modifier = Modifier.testTag("account_delete_${account.id}")) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}


@Composable
internal fun AccountPrimaryActionContent(state: AccountSettingsState, onTest: () -> Unit, onSave: () -> Unit) {
    Button(
        onClick = { if (state.primaryAction == AccountPrimaryAction.SAVE) onSave() else onTest() },
        enabled = state.primaryEnabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().testTag("account_primary_action"),
    ) {
        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp).testTag("account_primary_progress"))
        Text(stringResource(when (state.primaryAction) {
            AccountPrimaryAction.TEST -> R.string.test_connection
            AccountPrimaryAction.TESTING -> R.string.connection_testing
            AccountPrimaryAction.SAVE -> R.string.account_save_settings
            AccountPrimaryAction.SAVING -> R.string.account_saving
            AccountPrimaryAction.SAVED -> R.string.account_saved_action
        }))
    }
    AccountStatus(state.testStatus, R.string.connection_testing, R.string.connection_test_success,
        connectionFailureLabel(state.failureKind), "account_connection_status")
    AccountStatus(state.saveStatus, R.string.account_saving, R.string.account_saved,
        saveFailureLabel(state.saveFailure), "account_save_status")
    if (!state.busy) {
        Column(Modifier.testTag("account_connection_disabled_reason")) {
            state.editor.connectionIssues.forEach { issue ->
                Text(stringResource(if (issue == AccountConnectionIssue.PASSWORD && state.credentialLoading)
                    R.string.account_password_checking else connectionIssueLabel(issue)))
            }
            if (state.primaryAction == AccountPrimaryAction.SAVE && state.editor.name.isBlank()) {
                Text(stringResource(R.string.validation_name))
            }
        }
    }
}

private fun connectionIssueLabel(issue: AccountConnectionIssue): Int = when (issue) {
    AccountConnectionIssue.HOST -> R.string.connection_requires_host
    AccountConnectionIssue.PORT -> R.string.connection_requires_port
    AccountConnectionIssue.USERNAME -> R.string.connection_requires_username
    AccountConnectionIssue.PASSWORD -> R.string.connection_requires_password
}

private fun saveFailureLabel(kind: AccountSaveFailure?): Int = when (kind) {
    AccountSaveFailure.CREDENTIAL -> R.string.account_credential_save_failed
    AccountSaveFailure.ROLLBACK -> R.string.account_save_rollback_failed
    else -> R.string.account_save_failed
}

@Composable
internal fun AccountPasswordField(value: String, onValueChange: (String) -> Unit, visible: Boolean, onToggle: () -> Unit, saved: Boolean, enabled: Boolean, credentialLoading: Boolean = false) {
    direct.kabu.alertkun.ui.common.SavedPasswordField(value, onValueChange, visible, onToggle, saved, enabled,
        credentialLoading, R.string.account_password, "account_password", "account")
}

internal typealias SavedPasswordMaskTransformation = direct.kabu.alertkun.ui.common.SavedPasswordMaskTransformation
@Composable
private fun AccountStatus(status: AccountOperationStatus, running: Int, success: Int, failure: Int, tag: String) {
    if (status == AccountOperationStatus.IDLE) return
    Row(Modifier.testTag(tag), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == AccountOperationStatus.RUNNING) CircularProgressIndicator(Modifier.size(20.dp))
        Text(stringResource(when (status) { AccountOperationStatus.RUNNING -> running; AccountOperationStatus.SUCCESS -> success; else -> failure }))
    }
}

private fun connectionFailureLabel(kind: MonitoringFailureKind?): Int = when (kind) {
    MonitoringFailureKind.AUTHENTICATION_FAILURE -> R.string.connection_auth_failed
    MonitoringFailureKind.TLS_FAILURE -> R.string.connection_tls_failed
    MonitoringFailureKind.CERTIFICATE_UNTRUSTED -> R.string.tls_ca_failed
    MonitoringFailureKind.CERTIFICATE_EXPIRED -> R.string.tls_expired
    MonitoringFailureKind.HOSTNAME_MISMATCH -> R.string.tls_hostname_failed
    MonitoringFailureKind.DNS_FAILURE -> R.string.connection_dns_failed
    MonitoringFailureKind.CONNECTION_TIMEOUT -> R.string.connection_timeout
    MonitoringFailureKind.PROTOCOL_ERROR -> R.string.connection_protocol_failed
    else -> R.string.connection_test_failure
}

private fun defaultPort(protocol: MailProtocol): Int = when (protocol) {
    MailProtocol.POP3 -> 110
    MailProtocol.POP3S -> 995
    MailProtocol.IMAP4 -> 143
    MailProtocol.IMAPS -> 993
}

private fun protocolLabel(protocol: MailProtocol): Int = when (protocol) {
    MailProtocol.POP3 -> R.string.protocol_pop3
    MailProtocol.POP3S -> R.string.protocol_pop3s
    MailProtocol.IMAP4 -> R.string.protocol_imap4
    MailProtocol.IMAPS -> R.string.protocol_imaps
}
