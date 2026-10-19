package direct.kabu.alertkun.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.ui.main.formatMonitoringTimestamp

@Composable
fun ReceivedMailDetailScreen(mailId: String?, onBack: () -> Unit, viewModel: ReceivedMailViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReceivedMailDetailContent(mailId, state, onBack)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReceivedMailDetailContent(mailId: String?, state: ReceivedMailUiState, onBack: () -> Unit) {
    // The same Room flow as the list excludes locally deleted rows and reacts to cleanup.
    val mail = state.mails.firstOrNull { it.id == mailId }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.received_mail_detail)) }, navigationIcon = {
            TextButton(onClick = onBack, modifier = Modifier.testTag("mail_detail_back")) { Text(stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(16.dp).testTag("mail_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.testTag("mail_detail_loading"))
                state.loadFailed -> Text(stringResource(R.string.received_mail_load_failed), Modifier.testTag("mail_detail_error"))
                mail == null -> Text(stringResource(R.string.received_mail_not_found), Modifier.testTag("mail_detail_missing"))
                else -> {
                    if (mail.processingState == direct.kabu.alertkun.model.MailProcessingState.UNSUPPORTED) {
                        Text(stringResource(R.string.received_mail_unsupported), Modifier.testTag("mail_detail_unsupported"))
                    }
                    Text(mail.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.received_mail_no_subject),
                        Modifier.testTag("mail_detail_subject"), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.received_mail_from,
                        mail.from?.takeIf { it.isNotBlank() } ?: stringResource(R.string.received_mail_unknown_sender)),
                        Modifier.testTag("mail_detail_from"))
                    Text(stringResource(R.string.received_mail_timestamp, formatMonitoringTimestamp(mail.receivedAtEpochMillis)),
                        Modifier.testTag("mail_detail_timestamp"))
                    Text(stringResource(R.string.target_body), style = MaterialTheme.typography.titleMedium)
                    Text(mail.body?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.received_mail_no_body),
                        Modifier.fillMaxWidth().testTag("mail_detail_body"))
                }
            }
        }
    }
}
