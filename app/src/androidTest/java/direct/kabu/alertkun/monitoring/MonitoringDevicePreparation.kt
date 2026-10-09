package direct.kabu.alertkun.monitoring

import android.app.Application
import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import direct.kabu.alertkun.model.MonitoringSettings
import direct.kabu.alertkun.ui.settings.MonitoringSettingsViewModel
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Test-only confirmation; never enqueues a second request or blocks the production UI. */
internal object MonitoringDevicePreparation {
    private const val TIMEOUT_MILLIS = 10_000L

    suspend fun apply(app: Application, before: MonitoringSettings, enabled: Boolean) {
        val started = SystemClock.elapsedRealtime()
        fun observe(event: String, fields: Map<String, Any?> = emptyMap()) = MonitoringObservation.record(app, event,
            "trigger" to "INSTRUMENTATION", "uniqueName" to MonitoringScheduler.PERIODIC_WORK_NAME,
            "monitoringEnabled" to enabled, *fields.toList().toTypedArray())
        observe("DEVICE_MONITORING_CONFIRM_START")
        try {
            val viewModel = MonitoringSettingsViewModel(app)
            val update = viewModel.update(before.pollIntervalMinutes, before.consecutiveFailureThreshold,
                enabled, before.checkImmediatelyOnAppStart, before.healthNotificationEnabled)
            withTimeout(TIMEOUT_MILLIS) { update.join() }
            check(!update.isCancelled) { "Monitoring settings update failed" }
            check(SettingsRepository(app).settings.first() == before.copy(monitoringEnabled = enabled)) {
                "Monitoring settings did not reach the requested state"
            }
            val execution = MonitoringStatusRepository(app).executionStatus()
            check(execution.monitoringEnabled == enabled) { "Monitoring execution state differs from settings" }
            val operations = viewModel.monitoringUpdateOperations
            check(operations.isNotEmpty()) { "Monitoring update returned no WorkManager Operation" }
            val manager = WorkManager.getInstance(app)
            awaitPeriodicState(operations.map { it.result }, enabled, execution.scheduleGeneration,
                MonitoringScheduler.backupIntervalMinutes(before.pollIntervalMinutes),
                (TIMEOUT_MILLIS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1),
                query = { remaining -> withContext(Dispatchers.IO) {
                    manager.getWorkInfosForUniqueWork(MonitoringScheduler.PERIODIC_WORK_NAME).get(remaining, TimeUnit.MILLISECONDS)
                } }, observe = ::observe)
            check(SettingsRepository(app).settings.first().monitoringEnabled == enabled &&
                MonitoringStatusRepository(app).executionStatus().scheduleGeneration == execution.scheduleGeneration) {
                "Monitoring changed during confirmation"
            }
            observe("DEVICE_MONITORING_CONFIRM_SUCCESS", mapOf("generation" to execution.scheduleGeneration,
                "elapsedMs" to (SystemClock.elapsedRealtime() - started)))
        } catch (error: Throwable) {
            observe("DEVICE_MONITORING_CONFIRM_FAILURE", mapOf("exceptionClass" to error.javaClass.simpleName,
                "elapsedMs" to (SystemClock.elapsedRealtime() - started)))
            throw error
        }
    }

    suspend fun awaitPeriodicState(
        operations: List<ListenableFuture<*>>, enabled: Boolean, generation: Long, backupInterval: Int,
        timeoutMillis: Long = TIMEOUT_MILLIS, query: suspend (Long) -> List<WorkInfo>,
        observe: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    ) {
        require(operations.isNotEmpty()) { "A completed enqueue/cancel Operation is required" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).takeIf { it > 0 }
            ?: throw TimeoutException("Periodic backup confirmation timed out: ${MonitoringScheduler.PERIODIC_WORK_NAME}")
        operations.forEach { operation -> withContext(Dispatchers.IO) { operation.get(remaining(), TimeUnit.MILLISECONDS) } }
        observe("DEVICE_MONITORING_OPERATION_COMPLETED", mapOf("generation" to generation))
        var previousSnapshot: List<Pair<String, WorkInfo.State>>? = null
        while (true) {
            val infos = query(remaining())
            val snapshot = infos.map { it.id.toString() to it.state }
            if (snapshot != previousSnapshot) {
                if (infos.isEmpty()) observe("DEVICE_MONITORING_WORK_INFO", mapOf("state" to "MISSING", "generation" to generation))
                infos.forEach { info -> observe("DEVICE_MONITORING_WORK_INFO", mapOf("retainedWorkId" to info.id.toString(),
                    "state" to info.state.name, "generation" to generation)) }
                previousSnapshot = snapshot
            }
            val active = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            if (enabled) {
                check(active.size <= 1) { "Duplicate active Periodic backups" }
                if (active.singleOrNull()?.let {
                    it.periodicityInfo?.repeatIntervalMillis == backupInterval * 60_000L &&
                        "backup_generation_$generation" in it.tags && "backup_interval_$backupInterval" in it.tags
                } == true) return
            } else if (infos.all { it.state == WorkInfo.State.CANCELLED }) return
            delay(minOf(25L, remaining()))
        }
    }
}
