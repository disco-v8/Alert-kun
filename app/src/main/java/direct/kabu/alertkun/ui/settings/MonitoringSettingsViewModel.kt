package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.mail.common.DummyMailReceiver
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import direct.kabu.alertkun.monitoring.MonitoringHealthNotifier
import direct.kabu.alertkun.monitoring.MailCheckWorker
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import direct.kabu.alertkun.mail.common.MailCheckMode
import direct.kabu.alertkun.mail.common.MailReceiverSelection
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.data.database.AlertKunDatabase

class MonitoringSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val accounts = AccountRepository(AlertKunDatabase.getInstance(application).accountDao())
    private val mutableStartBlocked = MutableStateFlow(false)
    val startBlocked = mutableStartBlocked.asStateFlow()
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitoringSettings())
    /** Handles for device-test cleanup; normal UI keeps the existing asynchronous behavior. */
    internal var monitoringUpdateOperations: List<androidx.work.Operation> = emptyList()
        private set
    fun update(poll: Int, threshold: Int, enabled: Boolean, checkImmediatelyOnAppStart: Boolean,
        healthNotificationEnabled: Boolean) = viewModelScope.launch {
        monitoringUpdateOperations = emptyList()
        MailCheckWorker.executionMutex.withLock {
            mutableStartBlocked.value = false
            if (enabled && MailReceiverSelection(repository.settings.first().demoModeEnabled, accounts.accounts.first()).mode == MailCheckMode.NO_ACCOUNT) {
                mutableStartBlocked.value = true
                return@launch
            }
            repository.updateMonitoringSettings(poll, threshold, enabled, checkImmediatelyOnAppStart, healthNotificationEnabled)
            if (!healthNotificationEnabled) MonitoringHealthNotifier(getApplication()).cancel()
        }
        monitoringUpdateOperations = if (enabled) listOf(MonitoringScheduler.reschedule(getApplication(), poll))
            else MonitoringScheduler.stop(getApplication())
    }
    fun setDemoMode(enabled: Boolean) = viewModelScope.launch {
        MailCheckWorker.executionMutex.withLock {
            repository.updateDemoModeEnabled(enabled)
            mutableStartBlocked.value = false
        }
    }
    fun queueMatching() = viewModelScope.launch { if (repository.settings.first().demoModeEnabled) DummyMailReceiver.instance.queueMatchingMail() }
    fun queueNonMatching() = viewModelScope.launch { if (repository.settings.first().demoModeEnabled) DummyMailReceiver.instance.queueNonMatchingMail() }
    fun simulateFailure() = viewModelScope.launch { if (repository.settings.first().demoModeEnabled) DummyMailReceiver.instance.simulateNextFailure() }
    fun runNow() = MonitoringScheduler.runNow(getApplication())
}
