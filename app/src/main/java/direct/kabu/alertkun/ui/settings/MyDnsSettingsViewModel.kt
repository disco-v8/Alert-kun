package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.repository.MyDnsSaveException
import direct.kabu.alertkun.data.repository.MyDnsSaveFailure
import direct.kabu.alertkun.data.repository.MyDnsSettingsRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import direct.kabu.alertkun.push.FcmRegistrationCoordinator
import direct.kabu.alertkun.push.GatewayRegistrationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MyDnsSettingsUiState(
    val masterId: String = "",
    val password: String = "",
    val passwordSaved: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val failed: Boolean = false,
    val rollbackFailed: Boolean = false,
    val gatewayResult: GatewayRegistrationKind? = null,
) {
    override fun toString() = "MyDnsSettingsUiState(redacted)"
}

class MyDnsSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MyDnsSettingsRepository(SettingsRepository(application), AndroidKeystoreCredentialStore(application))
    private val mutableState = MutableStateFlow(MyDnsSettingsUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            FcmRegistrationCoordinator.registrationResult.collect { result ->
                mutableState.value = mutableState.value.copy(gatewayResult = result)
            }
        }
        viewModelScope.launch {
            try {
                val saved = repository.read()
                mutableState.value = mutableState.value.copy(masterId = saved.masterId, passwordSaved = saved.passwordSaved, loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
        }
    }

    fun editMasterId(value: String) {
        if (!state.value.loading && !state.value.saving) mutableState.value = state.value.copy(masterId = value, saved = false, failed = false, rollbackFailed = false)
    }

    fun editPassword(value: String) {
        if (!state.value.loading && !state.value.saving) mutableState.value = state.value.copy(password = value, saved = false, failed = false, rollbackFailed = false)
    }

    fun clearPassword() { mutableState.value = state.value.copy(password = "") }

    fun save() {
        if (state.value.loading || state.value.saving) return
        val input = state.value
        mutableState.value = input.copy(saving = true, saved = false, failed = false, rollbackFailed = false)
        viewModelScope.launch {
            try {
                val saved = repository.save(input.masterId, input.password)
                mutableState.value = state.value.copy(masterId = saved.masterId, password = "", passwordSaved = saved.passwordSaved, saving = false, saved = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = state.value.copy(saving = false, failed = true,
                    rollbackFailed = (error as? MyDnsSaveException)?.kind == MyDnsSaveFailure.ROLLBACK)
            }
        }
    }
}
