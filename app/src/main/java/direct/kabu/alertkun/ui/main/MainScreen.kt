package direct.kabu.alertkun.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.testTag
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
    pollIntervalMinutes: Int,
    @Suppress("UNUSED_PARAMETER") timeZoneRevision: Long,
    onOpenMail: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    continuousAlarmActive: Boolean = false,
    onStopAlarm: () -> Unit = {},
) {
    val status = if (monitoringState.isMonitoring) {
        stringResource(R.string.monitoring_active_with_interval, pollIntervalMinutes)
    } else {
        stringResource(R.string.monitoring_stopped)
    }
    val lastSuccessfulCheck = monitoringState.lastSuccessfulCheckAtEpochMillis
        ?.let(::formatMonitoringTimestamp)
        ?: stringResource(R.string.not_available)
    val nextCheckAt = if (monitoringState.referenceIntervalMinutes != null &&
        monitoringState.referenceIntervalMinutes != pollIntervalMinutes) null else
        nextFcmCheckAt(monitoringState.lastFcmReceivedAtEpochMillis, pollIntervalMinutes,
            monitoringState.lastGatewayRegistrationAtEpochMillis)
    val nextScheduledCheck = when {
        !monitoringState.isMonitoring -> stringResource(R.string.next_check_none)
        nextCheckAt == null -> stringResource(R.string.next_check_pending)
        else -> stringResource(R.string.next_scheduled_check, formatMonitoringTimestamp(nextCheckAt))
    }

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
            if (continuousAlarmActive) {
                Text(stringResource(R.string.continuous_alarm_active),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = onStopAlarm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("main_stop_alarm"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleLarge) }
            }
            Text(
                text = status,
                modifier = Modifier.testTag("monitoring_status"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.last_check, lastSuccessfulCheck),
                modifier = Modifier.testTag("monitoring_last_check"),
            )
            Text(nextScheduledCheck, modifier = Modifier.testTag("monitoring_next_check"))

            NavigationButton(label = stringResource(R.string.received_mail), onClick = onOpenMail)
            NavigationButton(label = stringResource(R.string.rule_settings), onClick = onOpenRules)
            NavigationButton(label = stringResource(R.string.account_settings), onClick = onOpenAccount)
            NavigationButton(label = stringResource(R.string.settings), onClick = onOpenSettings,
                modifier = Modifier.testTag("main_open_settings"))
        }
    }
}

@Composable
private fun NavigationButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    AlertKunTheme {
        MainScreen(
            monitoringState = MonitoringState(false, null, null, 0),
            pollIntervalMinutes = 5,
            timeZoneRevision = 0L,
            onOpenMail = {},
            onOpenRules = {},
            onOpenAccount = {},
            onOpenSettings = {},
        )
    }
}
