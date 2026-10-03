package direct.kabu.alertkun.ui.rule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.model.AlarmMode
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.RuleTarget
import direct.kabu.alertkun.model.VibrationPattern
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleEditUiState(
    val rule: AlertRule = newRule(),
    val loading: Boolean = false,
    val validationMessage: String? = null,
) {
    companion object {
        fun newRule() = AlertRule(
            id = UUID.randomUUID().toString(),
            name = "",
            searchText = "",
            target = RuleTarget.SUBJECT,
            priority = 1,
            soundUri = null,
            vibrationPattern = VibrationPattern.NONE,
            alarmMode = AlarmMode.ONCE,
            enabled = true,
        )
    }
}

class RuleEditViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(AlertKunDatabase.getInstance(application).ruleDao())
    private val _uiState = MutableStateFlow(RuleEditUiState())
    val uiState = _uiState.asStateFlow()

    fun load(ruleId: String?, copy: Boolean) {
        if (ruleId == null) {
            _uiState.value = RuleEditUiState()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, validationMessage = null) }
            val existing = repository.get(ruleId)
            _uiState.value = when {
                existing == null -> RuleEditUiState(validationMessage = "Rule not found")
                copy -> RuleEditUiState(rule = existing.copy(
                    id = UUID.randomUUID().toString(),
                    name = "${existing.name} コピー",
                ))
                else -> RuleEditUiState(rule = existing)
            }
        }
    }

    fun update(transform: (AlertRule) -> AlertRule) {
        _uiState.update { state -> state.copy(rule = transform(state.rule), validationMessage = null) }
    }

    fun save(onSaved: () -> Unit) {
        val rule = _uiState.value.rule
        val validationMessage = validate(rule)
        if (validationMessage != null) {
            _uiState.update { it.copy(validationMessage = validationMessage) }
            return
        }
        viewModelScope.launch {
            try {
                repository.upsert(rule)
                onSaved()
            } catch (_: IllegalArgumentException) {
                _uiState.update { it.copy(validationMessage = "Unable to save rule") }
            }
        }
    }

    private fun validate(rule: AlertRule): String? = when {
        rule.name.isBlank() -> "Name is required"
        rule.searchText.isBlank() -> "Search text is required"
        rule.priority < 1 -> "Priority must be at least 1"
        else -> null
    }
}
