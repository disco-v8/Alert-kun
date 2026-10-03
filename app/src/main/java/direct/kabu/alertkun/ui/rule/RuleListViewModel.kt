package direct.kabu.alertkun.ui.rule
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.model.AlertRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class RuleListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(AlertKunDatabase.getInstance(application).ruleDao())

    val rules = repository.rules.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun setEnabled(rule: AlertRule, enabled: Boolean) {
        viewModelScope.launch { repository.upsert(rule.copy(enabled = enabled)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
