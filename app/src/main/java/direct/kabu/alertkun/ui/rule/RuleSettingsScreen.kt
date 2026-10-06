package direct.kabu.alertkun.ui.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RuleSettingsScreen(
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onCopyRule: (String) -> Unit,
    viewModel: RuleListViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var rulePendingDelete by remember { mutableStateOf<AlertRule?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.rule_settings)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) },
        floatingActionButton = { ExtendedFloatingActionButton(icon = { Text("+") }, onClick = onAddRule, text = { Text(stringResource(R.string.add_rule)) }) },
    ) { innerPadding ->
        RuleListContent(rules, onEditRule, onCopyRule, viewModel::setEnabled, { rulePendingDelete = it }, Modifier.fillMaxSize().padding(innerPadding))
    }
    rulePendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            title = { Text(stringResource(R.string.delete_rule_title)) }, text = { Text(stringResource(R.string.delete_rule_message, rule.name)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(rule.id); rulePendingDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { rulePendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun RuleListContent(
    rules: List<AlertRule>, onEditRule: (String) -> Unit, onCopyRule: (String) -> Unit,
    onEnabledChanged: (AlertRule, Boolean) -> Unit, onDeleteRule: (AlertRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rules.isEmpty()) {
        Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.no_rules)) }
    } else {
        LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { rule -> RuleListItem(rule, onEditRule, onCopyRule, onEnabledChanged, onDeleteRule) }
        }
    }
}

@Composable
private fun RuleListItem(rule: AlertRule, onEditRule: (String) -> Unit, onCopyRule: (String) -> Unit, onEnabledChanged: (AlertRule, Boolean) -> Unit, onDeleteRule: (AlertRule) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name)
                    MailColorSample(rule.backgroundColor)
                    Text(
                        stringResource(
                            R.string.rule_search_description,
                            targetLabel(rule.target),
                            rule.searchText,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.rule_configuration_summary,
                            stringResource(R.string.priority),
                            rule.priority,
                            alarmModeLabel(rule.alarmMode),
                            vibrationLabel(rule.vibrationPattern),
                        ),
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = { onEnabledChanged(rule, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    modifier = Modifier.testTag("rule_edit_${rule.id}"),
                    onClick = { onEditRule(rule.id) },
                ) { Text(stringResource(R.string.edit_rule)) }
                TextButton(
                    modifier = Modifier.testTag("rule_copy_${rule.id}"),
                    onClick = { onCopyRule(rule.id) },
                ) { Text(stringResource(R.string.copy_rule)) }
                TextButton(
                    modifier = Modifier.testTag("rule_delete_${rule.id}"),
                    onClick = { onDeleteRule(rule) },
                ) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun targetLabel(value: RuleTarget): String = stringResource(
    when (value) {
        RuleTarget.SUBJECT -> R.string.target_subject
        RuleTarget.BODY -> R.string.target_body
        RuleTarget.SUBJECT_AND_BODY -> R.string.target_subject_and_body
    },
)

@Composable
private fun alarmModeLabel(value: AlarmMode): String = direct.kabu.alertkun.ui.settings.alarmCountLabel(value)

@Composable
private fun vibrationLabel(value: VibrationPattern): String = stringResource(
    when (value) {
        VibrationPattern.NONE -> R.string.vibration_none
        VibrationPattern.PATTERN_1 -> R.string.vibration_pattern_1
        VibrationPattern.PATTERN_2 -> R.string.vibration_pattern_2
        VibrationPattern.PATTERN_3 -> R.string.vibration_pattern_3
    },
)
