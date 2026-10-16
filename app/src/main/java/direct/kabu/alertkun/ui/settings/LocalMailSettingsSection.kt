package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import direct.kabu.alertkun.R
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.LocalMailRetention
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.ui.account.ServerDeletePolicySelector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LocalMailSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    val policy = repository.settings.map { LocalMailRetention.policy(it.mailRetentionDays) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerDeletePolicy.AFTER_1_WEEK)

    fun select(policy: ServerDeletePolicy) { viewModelScope.launch { repository.updateMailRetentionPolicy(policy) } }
}

@Composable
internal fun LocalMailSettingsSection(viewModel: LocalMailSettingsViewModel = viewModel()) {
    val policy by viewModel.policy.collectAsStateWithLifecycle()
    ServerDeletePolicySelector(policy, true, label = R.string.local_mail_delete_policy,
        tag = "local_mail_delete_policy", showServerHint = false, onSelected = viewModel::select)
}
