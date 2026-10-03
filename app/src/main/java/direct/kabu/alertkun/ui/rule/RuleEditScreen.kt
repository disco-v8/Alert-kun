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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import direct.kabu.alertkun.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RuleEditScreen(
    ruleId: String?,
    copy: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RuleEditViewModel = viewModel(),
) {
    val copyNameFormat = stringResource(R.string.copy_rule_name_format)
    LaunchedEffect(ruleId, copy, copyNameFormat) {
        viewModel.load(ruleId, copy, copyNameFormat)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rule = state.rule

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (copy) R.string.copy_rule else if (ruleId == null) R.string.create_rule else R.string.edit_rule)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
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
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = rule.searchText,
                onValueChange = { value -> viewModel.update { it.copy(searchText = value) } },
                label = { Text(stringResource(R.string.search_text)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            EnumSelector(
                label = stringResource(R.string.search_target),
                selected = rule.target,
                options = RuleTarget.entries,
                labelFor = ::targetLabel,
            ) { target -> viewModel.update { it.copy(target = target) } }
            OutlinedTextField(
                value = rule.priority.toString(),
                onValueChange = { value -> viewModel.update { it.copy(priority = value.toIntOrNull() ?: 0) } },
                label = { Text(stringResource(R.string.priority)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            EnumSelector(
                label = stringResource(R.string.vibration),
                selected = rule.vibrationPattern,
                options = VibrationPattern.entries,
                labelFor = ::vibrationLabel,
            ) { pattern -> viewModel.update { it.copy(vibrationPattern = pattern) } }
            EnumSelector(
                label = stringResource(R.string.alarm_mode),
                selected = rule.alarmMode,
                options = AlarmMode.entries,
                labelFor = ::alarmModeLabel,
            ) { mode -> viewModel.update { it.copy(alarmMode = mode) } }
            Row {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = { value -> viewModel.update { it.copy(enabled = value) } })
            }
            Text(stringResource(R.string.sound_later))
            state.error?.let { error -> Text(stringResource(error.messageResource())) }
            Button(
                onClick = { viewModel.save(onSaved) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun RuleEditError.messageResource(): Int = when (this) {
    RuleEditError.RULE_NOT_FOUND -> R.string.validation_rule_not_found
    RuleEditError.NAME_REQUIRED -> R.string.validation_name
    RuleEditError.SEARCH_TEXT_REQUIRED -> R.string.validation_search
    RuleEditError.PRIORITY_INVALID -> R.string.validation_priority
    RuleEditError.SAVE_FAILED -> R.string.validation_save_rule
}

@Composable private fun targetLabel(value: RuleTarget) = stringResource(when (value) { RuleTarget.SUBJECT -> R.string.target_subject; RuleTarget.BODY -> R.string.target_body; RuleTarget.SUBJECT_AND_BODY -> R.string.target_subject_and_body })
@Composable private fun vibrationLabel(value: VibrationPattern) = stringResource(when (value) { VibrationPattern.NONE -> R.string.vibration_none; VibrationPattern.PATTERN_1 -> R.string.vibration_pattern_1; VibrationPattern.PATTERN_2 -> R.string.vibration_pattern_2; VibrationPattern.PATTERN_3 -> R.string.vibration_pattern_3 })
@Composable private fun alarmModeLabel(value: AlarmMode) = stringResource(if (value == AlarmMode.ONCE) R.string.alarm_once else R.string.alarm_until_stopped)

@Composable
private fun <T> EnumSelector(
    label: String,
    selected: T,
    options: List<T>,
    labelFor: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label)
        OutlinedButton(onClick = { expanded = true }) { Text(labelFor(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option)) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}
