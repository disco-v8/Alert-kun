package direct.kabu.alertkun.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import direct.kabu.alertkun.data.preferences.settingsDataStore
import direct.kabu.alertkun.model.MonitoringState
import direct.kabu.alertkun.monitoring.monitoringDebugLog
import direct.kabu.alertkun.monitoring.MailCheckTrigger
import direct.kabu.alertkun.monitoring.BackupMonitoringPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Durable execution status, intentionally separate from user-editable monitoring settings. */
class MonitoringStatusRepository private constructor(private val store: DataStore<Preferences>, private val log: (String) -> Unit) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore, { monitoringDebugLog(context, it) })
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
        MonitoringExecutionStatus(values[ENABLED] ?: false, values[GENERATION] ?: 0L, values[SCHEDULE_KIND],
            values[LAST_STARTED]?.takeIf { it > 0 }, values[LAST_COMPLETED]?.takeIf { it > 0 },
            values[LAST_SUCCESS]?.takeIf { it > 0 },
            values[LAST_SUCCESS_TRIGGER]?.let { name -> MailCheckTrigger.entries.find { it.name == name } },
            values[LAST_SUCCESS_GENERATION], values[NEXT_SCHEDULED]?.takeIf { it > 0 }, values[BACKUP_INTERVAL], values[LAST_ATTEMPT_SUCCESS])
    }
    /** Increments the generation before cancellation/enqueue work can race with an old worker. */
    suspend fun changeSchedule(enabled: Boolean, nextScheduledAt: Long?, scheduleKind: String? = null, backupIntervalMinutes: Int? = null): Long {
        var generation = 0L
        store.edit {
            generation = (it[GENERATION] ?: 0L) + 1L
            it[GENERATION] = generation
            it[ENABLED] = enabled
            it[NEXT_SCHEDULED] = nextScheduledAt ?: 0L
            if (enabled) it[SCHEDULE_KIND] = scheduleKind ?: "" else it.remove(SCHEDULE_KIND)
            if (enabled && backupIntervalMinutes != null) it[BACKUP_INTERVAL] = backupIntervalMinutes else it.remove(BACKUP_INTERVAL)
        }
        log("status changeSchedule enabled=$enabled generation=$generation nextMs=$nextScheduledAt kind=$scheduleKind backupInterval=$backupIntervalMinutes")
        return generation
    }
    suspend fun recordCheckStarted(startedAt: Long, expectedGeneration: Long) = store.edit {
        if ((it[GENERATION] ?: 0L) == expectedGeneration) {
            it[LAST_STARTED] = startedAt
            it[LAST_ATTEMPT_SUCCESS] = false
        }
    }
    suspend fun setBackupScheduleIfCurrent(target: Long, generation: Long, intervalMinutes: Int): Boolean {
        var changed = false
        store.edit {
            if (it[ENABLED] == true && it[GENERATION] == generation) {
                it[NEXT_SCHEDULED] = target; it[BACKUP_INTERVAL] = intervalMinutes; changed = true
            }
        }
        return changed
    }
    suspend fun advanceBackupWindow(now: Long, generation: Long, intervalMinutes: Int) {
        store.edit {
            if (it[ENABLED] == true && it[GENERATION] == generation && it[BACKUP_INTERVAL] == intervalMinutes) {
                it[NEXT_SCHEDULED] = BackupMonitoringPolicy.nextTarget(now, it[NEXT_SCHEDULED], intervalMinutes)
            }
        }
    }
    suspend fun recordSuccess(completedAt: Long, trigger: MailCheckTrigger = MailCheckTrigger.MANUAL, expectedGeneration: Long? = null): MonitoringState {
        store.edit {
            if (expectedGeneration != null && (it[GENERATION] ?: 0L) != expectedGeneration) return@edit
            it[LAST_SUCCESS] = completedAt; it[LAST_COMPLETED] = completedAt; it[FAILURE_COUNT] = 0; it[FAILURE_REPORTED] = false
            it[LAST_SUCCESS_TRIGGER] = trigger.name
            it[LAST_SUCCESS_GENERATION] = it[GENERATION] ?: 0L
            it[LAST_ATTEMPT_SUCCESS] = true
        }
        log("status success completedMs=$completedAt trigger=$trigger expectedGeneration=$expectedGeneration")
        return current()
    }
    suspend fun recordFailure(completedAt: Long, threshold: Int, expectedGeneration: Long? = null): FailureUpdate {
        var notify = false
        store.edit {
            if (expectedGeneration != null && (it[GENERATION] ?: 0L) != expectedGeneration) return@edit
            val count = (it[FAILURE_COUNT] ?: 0) + 1
            it[FAILURE_COUNT] = count
            it[LAST_COMPLETED] = completedAt
            it[LAST_ATTEMPT_SUCCESS] = false
            if (count >= threshold && !(it[FAILURE_REPORTED] ?: false)) { it[FAILURE_REPORTED] = true; notify = true }
        }
        log("status failure completedMs=$completedAt expectedGeneration=$expectedGeneration")
        return FailureUpdate(current(), notify)
    }
    companion object {
        internal fun forStore(store: DataStore<Preferences>) = MonitoringStatusRepository(store, {})
        private val ENABLED = booleanPreferencesKey("monitoring_status_enabled")
        private val LAST_SUCCESS = longPreferencesKey("monitoring_last_success")
        private val LAST_SUCCESS_TRIGGER = stringPreferencesKey("monitoring_last_success_trigger")
        private val LAST_ATTEMPT_SUCCESS = booleanPreferencesKey("monitoring_last_attempt_success")
        private val LAST_SUCCESS_GENERATION = longPreferencesKey("monitoring_last_success_generation")
        private val BACKUP_INTERVAL = intPreferencesKey("monitoring_backup_interval_minutes")
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
    val lastSuccessfulCheckAtEpochMillis: Long? = null,
    val lastSuccessfulTrigger: MailCheckTrigger? = null,
    val lastSuccessfulGeneration: Long? = null,
    val nextBackupAtEpochMillis: Long? = null,
    val backupIntervalMinutes: Int? = null,
    val lastAttemptSucceeded: Boolean? = null,
)
