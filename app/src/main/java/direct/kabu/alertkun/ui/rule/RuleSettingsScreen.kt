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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.model.AlertRule

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
        topBar = { TopAppBar(title = { Text("Rule settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(icon = { Text("+") }, onClick = onAddRule, text = { Text("Add rule") }) },
    ) { innerPadding ->
        RuleListContent(rules, onEditRule, onCopyRule, viewModel::setEnabled, { rulePendingDelete = it }, Modifier.fillMaxSize().padding(innerPadding))
    }
    rulePendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            title = { Text("Delete rule?") }, text = { Text("${rule.name} will be deleted.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(rule.id); rulePendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { rulePendingDelete = null }) { Text("Cancel") } },
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
        Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("No rules yet") }
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
                    Text("${rule.target}: ${rule.searchText}")
                    Text("Priority ${rule.priority} · ${rule.alarmMode} · ${rule.vibrationPattern}")
                }
                Switch(checked = rule.enabled, onCheckedChange = { onEnabledChanged(rule, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEditRule(rule.id) }) { Text("Edit") }
                TextButton(onClick = { onCopyRule(rule.id) }) { Text("Copy") }
                TextButton(onClick = { onDeleteRule(rule) }) { Text("Delete") }
            }
        }
    }
}
