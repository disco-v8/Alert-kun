package direct.kabu.alertkun.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One bounded check or backup skip, always using the same process-wide execution mutex. */
class MailCheckWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = executionMutex.withLock {
        // All settings and success attribution are read after acquiring this shared lock.
        val settings = SettingsRepository(applicationContext).settings.first()
        val status = MonitoringStatusRepository(applicationContext)
        val scheduled = inputData.getBoolean(MonitoringScheduler.KEY_SCHEDULED, false)
        val generation = inputData.getLong(MonitoringScheduler.KEY_GENERATION, -1L)
        val startupRebase = inputData.getBoolean(MonitoringScheduler.KEY_STARTUP_REBASE, false)
        val trigger = MailCheckTrigger.fromInput(inputData.getString(MonitoringScheduler.KEY_TRIGGER), scheduled, startupRebase)
        val execution = status.executionStatus()
        monitoringDebugLog(applicationContext, "worker start id=$id trigger=$trigger scheduled=$scheduled generation=$generation startup=$startupRebase attempt=$runAttemptCount")
        if (!BackupMonitoringPolicy.mayExecute(trigger, settings.monitoringEnabled, execution.monitoringEnabled,
                execution.scheduleGeneration, generation)) {
            monitoringDebugLog(applicationContext, "worker skip id=$id trigger=$trigger generation mismatch/monitoring OFF current=${execution.scheduleGeneration}")
            return@withLock Result.success()
        }
        val now = System.currentTimeMillis()
        val backupInterval = BackupMonitoringPolicy.intervalMinutes(settings.pollIntervalMinutes)
        val expectedGeneration = execution.scheduleGeneration
        if (trigger == MailCheckTrigger.SCHEDULED_WORK && BackupMonitoringPolicy.shouldSkipBackup(now, generation, backupInterval, execution)) {
            monitoringDebugLog(applicationContext, "scheduled backup skipped: successful FCM check already completed in current backup window id=$id windowStartMs=${BackupMonitoringPolicy.windowStart(now, execution.nextBackupAtEpochMillis, backupInterval)} successMs=${execution.lastSuccessfulCheckAtEpochMillis}")
            status.advanceBackupWindow(now, generation, backupInterval)
            MonitoringStateStore.update(status.current())
            return@withLock Result.success()
        }
        if (trigger == MailCheckTrigger.FCM && BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, settings.pollIntervalMinutes, execution)) {
            monitoringDebugLog(applicationContext, "FCM check skipped: recent successful backup already fulfilled this interval id=$id")
            return@withLock Result.success()
        }
        val startedAt = now
        monitoringDebugLog(applicationContext, "mail check start id=$id trigger=$trigger interval=${settings.pollIntervalMinutes} backupInterval=$backupInterval")
        status.recordCheckStarted(startedAt, expectedGeneration)
        try {
            val result = if (DeviceNetworkChecker.isAvailable(applicationContext)) {
                MailCheckCoordinatorFactory.create(applicationContext).check()
            } else {
                MailCheckResult(success = false, failureKind = MonitoringFailureKind.DEVICE_OFFLINE)
            }
            val completedAt = System.currentTimeMillis()
            monitoringDebugLog(applicationContext, "mail check end id=$id trigger=$trigger success=${result.success} failure=${result.failureKind} checked=${result.checkedCount} received=${result.receivedCount}")
            if (result.success) {
                MonitoringStateStore.update(status.recordSuccess(completedAt, trigger, expectedGeneration))
            } else {
                val failure = status.recordFailure(completedAt, settings.consecutiveFailureThreshold, expectedGeneration)
                MonitoringStateStore.update(failure.state)
                if (failure.shouldNotify) MonitoringFailureNotifier(applicationContext).post(settings.consecutiveFailureThreshold, completedAt)
            }
        } catch (cancelled: CancellationException) {
            monitoringDebugLog(applicationContext, "worker cancelled id=$id stopped=$isStopped")
            throw cancelled
        } catch (error: Throwable) {
            monitoringDebugLog(applicationContext, "worker exception id=$id type=${error.javaClass.simpleName}")
            val completedAt = System.currentTimeMillis()
            val failure = status.recordFailure(completedAt, settings.consecutiveFailureThreshold, expectedGeneration)
            MonitoringStateStore.update(failure.state)
            if (failure.shouldNotify) MonitoringFailureNotifier(applicationContext).post(settings.consecutiveFailureThreshold, completedAt)
        } finally {
            if (startupRebase) {
                MonitoringScheduler.enqueueRebasedScheduleIfCurrent(applicationContext,
                    StartupReconcilePolicy.targetAfterCheck(startedAt, settings.pollIntervalMinutes), generation, settings.pollIntervalMinutes)
            } else if (trigger == MailCheckTrigger.SCHEDULED_WORK) {
                status.advanceBackupWindow(System.currentTimeMillis(), generation, backupInterval)
                MonitoringStateStore.update(status.current())
            }
        }
        monitoringDebugLog(applicationContext, "worker end id=$id trigger=$trigger stopped=$isStopped")
        Result.success()
    }

    internal companion object { val executionMutex = Mutex() }
}
