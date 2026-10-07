package direct.kabu.alertkun.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-local display revision used only to redraw local-time formatted UI after a timezone change. */
object MonitoringTimeChangeStore {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision

    fun notifyTimezoneChanged() {
        mutableRevision.value++
    }
}
