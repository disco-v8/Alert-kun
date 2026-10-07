package direct.kabu.alertkun.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import direct.kabu.alertkun.data.preferences.settingsDataStore
import direct.kabu.alertkun.model.MonitoringState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Durable execution status, intentionally separate from user-editable monitoring settings. */
class MonitoringStatusRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    val state = store.data.map { values ->
        MonitoringState(
            isMonitoring = values[ENABLED] ?: false,
            lastSuccessfulCheckAtEpochMillis = values[LAST_SUCCESS]?.takeIf { it > 0 },
            nextScheduledCheckAtEpochMillis = values[NEXT_SCHEDULED]?.takeIf { it > 0 },
            consecutiveFailureCount = values[FAILURE_COUNT] ?: 0,
        )
    }

    suspend fun current(): MonitoringState = state.first()
    suspend fun executionStatus(): MonitoringExecutionStatus = store.data.first().let { values ->
        MonitoringExecutionStatus(values[ENABLED] ?: false, values[GENERATION] ?: 0L, values[SCHEDULE_KIND], values[LAST_STARTED]?.takeIf { it > 0 }, values[LAST_COMPLETED]?.takeIf { it > 0 })
    }
    /** Increments the generation before cancellation/enqueue work can race with an old worker. */
    suspend fun changeSchedule(enabled: Boolean, nextScheduledAt: Long?, scheduleKind: String? = null): Long {
        var generation = 0L
        store.edit {
            generation = (it[GENERATION] ?: 0L) + 1L
            it[GENERATION] = generation
            it[ENABLED] = enabled
            it[NEXT_SCHEDULED] = nextScheduledAt ?: 0L
            if (enabled) it[SCHEDULE_KIND] = scheduleKind ?: "" else it.remove(SCHEDULE_KIND)
        }
        return generation
    }
    suspend fun recordCheckStarted(startedAt: Long) = store.edit { it[LAST_STARTED] = startedAt }
    suspend fun setNextScheduled(nextScheduledAt: Long?) = store.edit { it[NEXT_SCHEDULED] = nextScheduledAt ?: 0L }
    suspend fun recordSuccess(completedAt: Long, nextScheduledAt: Long?, expectedGeneration: Long? = null): MonitoringState {
        store.edit {
            it[LAST_SUCCESS] = completedAt; it[LAST_COMPLETED] = completedAt; it[FAILURE_COUNT] = 0; it[FAILURE_REPORTED] = false
            if (expectedGeneration == null || it[GENERATION] == expectedGeneration) it[NEXT_SCHEDULED] = nextScheduledAt ?: 0L
        }
        return current()
    }
    suspend fun recordFailure(completedAt: Long, nextScheduledAt: Long?, threshold: Int, expectedGeneration: Long? = null): FailureUpdate {
        var notify = false
        store.edit {
            val count = (it[FAILURE_COUNT] ?: 0) + 1
            it[FAILURE_COUNT] = count
            it[LAST_COMPLETED] = completedAt
            if (expectedGeneration == null || it[GENERATION] == expectedGeneration) it[NEXT_SCHEDULED] = nextScheduledAt ?: 0L
            if (count >= threshold && !(it[FAILURE_REPORTED] ?: false)) { it[FAILURE_REPORTED] = true; notify = true }
        }
        return FailureUpdate(current(), notify)
    }
    companion object {
        private val ENABLED = booleanPreferencesKey("monitoring_status_enabled")
        private val LAST_SUCCESS = longPreferencesKey("monitoring_last_success")
        private val NEXT_SCHEDULED = longPreferencesKey("monitoring_next_scheduled")
        private val FAILURE_COUNT = intPreferencesKey("monitoring_failure_count")
        private val FAILURE_REPORTED = booleanPreferencesKey("monitoring_failure_reported")
        private val GENERATION = longPreferencesKey("monitoring_schedule_generation")
        private val SCHEDULE_KIND = androidx.datastore.preferences.core.stringPreferencesKey("monitoring_schedule_kind")
        private val LAST_STARTED = longPreferencesKey("monitoring_last_check_started")
        private val LAST_COMPLETED = longPreferencesKey("monitoring_last_check_completed")
    }
}

data class FailureUpdate(val state: MonitoringState, val shouldNotify: Boolean)
data class MonitoringExecutionStatus(
    val monitoringEnabled: Boolean,
    val scheduleGeneration: Long,
    val scheduleKind: String?,
    val lastCheckStartedAtEpochMillis: Long?,
    val lastCheckCompletedAtEpochMillis: Long?,
)
