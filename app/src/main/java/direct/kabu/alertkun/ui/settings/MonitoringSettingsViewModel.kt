package direct.kabu.alertkun.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.mail.common.DummyMailReceiver
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.monitoring.MonitoringScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitoringSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitoringSettings())
    fun update(poll: Int, threshold: Int, enabled: Boolean, checkImmediatelyOnAppStart: Boolean) = viewModelScope.launch {
        repository.update(settings.value.copy(pollIntervalMinutes = poll, consecutiveFailureThreshold = threshold, monitoringEnabled = enabled, checkImmediatelyOnAppStart = checkImmediatelyOnAppStart))
        if (enabled) MonitoringScheduler.reschedule(getApplication(), poll) else MonitoringScheduler.stop(getApplication())
    }
    fun queueMatching() = viewModelScope.launch { DummyMailReceiver.instance.queueMatchingMail() }
    fun queueNonMatching() = viewModelScope.launch { DummyMailReceiver.instance.queueNonMatchingMail() }
    fun simulateFailure() = viewModelScope.launch { DummyMailReceiver.instance.simulateNextFailure() }
    fun runNow() = MonitoringScheduler.runNow(getApplication())
}
