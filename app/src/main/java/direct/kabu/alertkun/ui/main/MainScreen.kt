package direct.kabu.alertkun.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import direct.kabu.alertkun.R
import direct.kabu.alertkun.model.MonitoringState
import direct.kabu.alertkun.ui.theme.AlertKunTheme

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainScreen(
    monitoringState: MonitoringState,
    onOpenMail: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = stringResource(
        if (monitoringState.isMonitoring) R.string.monitoring_active else R.string.monitoring_stopped,
    )
    val lastSuccessfulCheck = monitoringState.lastSuccessfulCheckAtEpochMillis?.toString()
        ?: stringResource(R.string.not_available)
    val nextScheduledCheck = monitoringState.nextScheduledCheckAtEpochMillis?.toString()
        ?: stringResource(R.string.not_available)

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.monitoring_status, status),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.last_successful_check, lastSuccessfulCheck))
            Text(stringResource(R.string.next_scheduled_check, nextScheduledCheck))

            NavigationButton(label = stringResource(R.string.received_mail), onClick = onOpenMail)
            NavigationButton(label = stringResource(R.string.rule_settings), onClick = onOpenRules)
            NavigationButton(label = stringResource(R.string.account_settings), onClick = onOpenAccount)
            NavigationButton(label = stringResource(R.string.settings), onClick = onOpenSettings)
        }
    }
}

@Composable
private fun NavigationButton(label: String, onClick: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    AlertKunTheme {
        MainScreen(
            monitoringState = MonitoringState(false, null, null, 0),
            onOpenMail = {},
            onOpenRules = {},
            onOpenAccount = {},
            onOpenSettings = {},
        )
    }
}
