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
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleEditUiState(
    val rule: AlertRule = newRule(),
    val loading: Boolean = false,
    val error: RuleEditError? = null,
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

enum class RuleEditError {
    RULE_NOT_FOUND,
    NAME_REQUIRED,
    SEARCH_TEXT_REQUIRED,
    PRIORITY_INVALID,
    SAVE_FAILED,
}

class RuleEditViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(AlertKunDatabase.getInstance(application).ruleDao())
    private val _uiState = MutableStateFlow(RuleEditUiState())
    val uiState = _uiState.asStateFlow()

    fun load(ruleId: String?, copy: Boolean, copyNameFormat: String) {
        if (ruleId == null) {
            _uiState.value = RuleEditUiState()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val existing = repository.get(ruleId)
            _uiState.value = when {
                existing == null -> RuleEditUiState(error = RuleEditError.RULE_NOT_FOUND)
                copy -> RuleEditUiState(rule = existing.copy(
                    id = UUID.randomUUID().toString(),
                    name = String.format(Locale.ROOT, copyNameFormat, existing.name),
                ))
                else -> RuleEditUiState(rule = existing)
            }
        }
    }

    fun update(transform: (AlertRule) -> AlertRule) {
        _uiState.update { state -> state.copy(rule = transform(state.rule), error = null) }
    }

    fun save(onSaved: () -> Unit) {
        val rule = _uiState.value.rule
        val validationError = validate(rule)
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }
        viewModelScope.launch {
            try {
                repository.upsert(rule)
                onSaved()
            } catch (_: IllegalArgumentException) {
                _uiState.update { it.copy(error = RuleEditError.SAVE_FAILED) }
            }
        }
    }

    private fun validate(rule: AlertRule): RuleEditError? = when {
        rule.name.isBlank() -> RuleEditError.NAME_REQUIRED
        rule.searchText.isBlank() -> RuleEditError.SEARCH_TEXT_REQUIRED
        rule.priority < 1 -> RuleEditError.PRIORITY_INVALID
        else -> null
    }
}
