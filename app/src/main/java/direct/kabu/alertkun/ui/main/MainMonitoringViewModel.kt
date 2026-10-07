package direct.kabu.alertkun.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.model.MonitoringState
import direct.kabu.alertkun.monitoring.MonitoringTimeChangeStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainMonitoringUiState(
    val monitoringState: MonitoringState = MonitoringState(false, null, null, 0),
    val settings: MonitoringSettings = MonitoringSettings(),
    val timeZoneRevision: Long = 0L,
)

/** Observes durable Worker updates rather than relying only on process-local state. */
class MainMonitoringViewModel(application: Application) : AndroidViewModel(application) {
    val state = combine(
        MonitoringStatusRepository(application).state,
        SettingsRepository(application).settings,
        MonitoringTimeChangeStore.revision,
    ) { monitoringState, settings, timeZoneRevision -> MainMonitoringUiState(monitoringState, settings, timeZoneRevision) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainMonitoringUiState())
}
