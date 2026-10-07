package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.model.MonitoringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MonitoringStateStore {
    private val mutableState = MutableStateFlow(MonitoringState(false, null, null, 0))
    val state: StateFlow<MonitoringState> = mutableState
    fun update(value: MonitoringState) { mutableState.value = value }
}
