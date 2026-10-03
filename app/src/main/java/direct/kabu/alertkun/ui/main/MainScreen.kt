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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("アラートくん") }) },
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
                text = "監視状態: ${monitoringState.statusText()}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("最終受信成功: ${monitoringState.lastSuccessfulCheckText()}")
            Text("次回受信予定: ${monitoringState.nextScheduledCheckText()}")

            NavigationButton(label = "受信メール", onClick = onOpenMail)
            NavigationButton(label = "ルール設定", onClick = onOpenRules)
            NavigationButton(label = "アカウント設定", onClick = onOpenAccount)
            NavigationButton(label = "その他設定", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun NavigationButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun MonitoringState.statusText(): String = if (isMonitoring) "監視中" else "停止中"

private fun MonitoringState.lastSuccessfulCheckText(): String =
    lastSuccessfulCheckAtEpochMillis?.toString() ?: "なし"

private fun MonitoringState.nextScheduledCheckText(): String =
    nextScheduledCheckAtEpochMillis?.toString() ?: "なし"

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    AlertKunTheme {
        MainScreen(
            monitoringState = MonitoringState(
                isMonitoring = false,
                lastSuccessfulCheckAtEpochMillis = null,
                nextScheduledCheckAtEpochMillis = null,
                consecutiveFailureCount = 0,
            ),
            onOpenMail = {},
            onOpenRules = {},
            onOpenAccount = {},
            onOpenSettings = {},
        )
    }
}
