package direct.kabu.alertkun.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.ui.main.formatMonitoringTimestamp
import androidx.compose.ui.unit.dp
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.ReceivedMail

@Composable @OptIn(ExperimentalMaterial3Api::class)
fun ReceivedMailScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ReceivedMailViewModel = viewModel(), onOpenMail: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.received_mail)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        if (state.loading || state.loadFailed || state.mails.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.testTag("received_mail_loading"))
                    state.loadFailed -> Text(stringResource(R.string.received_mail_load_failed), Modifier.testTag("received_mail_error"))
                    else -> Text(stringResource(R.string.no_received_mail), Modifier.testTag("received_mail_empty"))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag("received_mail_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.mails, key = { it.id }) { mail ->
                    ReceivedMailCard(mail, onClick = { onOpenMail(mail.id) })
                }
            }
        }
    }
}

@Composable
internal fun ReceivedMailCard(mail: ReceivedMail, onClick: () -> Unit = {}) {
    val colors = mail.backgroundColor.cardColors()
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("received_mail_${mail.id}"),
        colors = CardDefaults.cardColors(containerColor = colors.background, contentColor = colors.content)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (mail.processingState == direct.kabu.alertkun.model.MailProcessingState.UNSUPPORTED)
                stringResource(R.string.received_mail_unsupported) else
                mail.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.received_mail_no_subject), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.received_mail_from, mail.from?.takeIf { it.isNotBlank() } ?: stringResource(R.string.received_mail_unknown_sender)))
            Text(stringResource(R.string.received_mail_timestamp, formatMonitoringTimestamp(mail.receivedAtEpochMillis)))
        }
    }
}
