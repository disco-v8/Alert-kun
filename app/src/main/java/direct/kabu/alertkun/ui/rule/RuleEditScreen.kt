package direct.kabu.alertkun.ui.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RuleEditScreen(
    ruleId: String?,
    copy: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RuleEditViewModel = viewModel(),
) {
    LaunchedEffect(ruleId, copy) { viewModel.load(ruleId, copy) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rule = state.rule

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (copy) "Copy rule" else if (ruleId == null) "Create rule" else "Edit rule") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = rule.name,
                onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = rule.searchText,
                onValueChange = { value -> viewModel.update { it.copy(searchText = value) } },
                label = { Text("Search text") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            EnumSelector("Target", rule.target.name, RuleTarget.entries.map { it.name }) { value ->
                viewModel.update { it.copy(target = RuleTarget.valueOf(value)) }
            }
            OutlinedTextField(
                value = rule.priority.toString(),
                onValueChange = { value -> viewModel.update { it.copy(priority = value.toIntOrNull() ?: 0) } },
                label = { Text("Priority (smaller is higher)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            EnumSelector("Vibration", rule.vibrationPattern.name, VibrationPattern.entries.map { it.name }) { value ->
                viewModel.update { it.copy(vibrationPattern = VibrationPattern.valueOf(value)) }
            }
            EnumSelector("Alarm mode", rule.alarmMode.name, AlarmMode.entries.map { it.name }) { value ->
                viewModel.update { it.copy(alarmMode = AlarmMode.valueOf(value)) }
            }
            Row {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = { value -> viewModel.update { it.copy(enabled = value) } })
            }
            Text("Sound selection is available in a later phase.")
            state.validationMessage?.let { Text(it) }
            Button(
                onClick = { viewModel.save(onSaved) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun EnumSelector(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label)
        OutlinedButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}
