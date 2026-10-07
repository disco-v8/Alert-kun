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

/** One bounded mail check; scheduling is delegated to [MonitoringScheduler]. */
class MailCheckWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = executionMutex.withLock {
        val settings = SettingsRepository(applicationContext).settings.first()
        val status = MonitoringStatusRepository(applicationContext)
        val scheduled = inputData.getBoolean(MonitoringScheduler.KEY_SCHEDULED, false)
        val generation = inputData.getLong(MonitoringScheduler.KEY_GENERATION, -1L)
        val startupRebase = inputData.getBoolean(MonitoringScheduler.KEY_STARTUP_REBASE, false)
        val expectedGeneration = generation.takeIf { scheduled }
        var execution = status.executionStatus()
        if (scheduled && !MonitoringExecutionPolicy.mayReschedule(execution.monitoringEnabled, execution.scheduleGeneration, generation)) {
            return@withLock Result.success()
        }
        val now = System.currentTimeMillis()
        if (scheduled && !startupRebase && MonitoringExecutionPolicy.shouldSkipScheduled(now, execution.lastCheckStartedAtEpochMillis, execution.lastCheckCompletedAtEpochMillis, settings.pollIntervalMinutes)) {
            rescheduleShortIfCurrent(settings.pollIntervalMinutes, generation, execution.lastCheckStartedAtEpochMillis)
            return@withLock Result.success()
        }
        val startedAt = now
        status.recordCheckStarted(startedAt)
        val nextAt = if (settings.monitoringEnabled) MonitoringExecutionPolicy.calculateNextTarget(startedAt, settings.pollIntervalMinutes) else null
        try {
            val result = if (DeviceNetworkChecker.isAvailable(applicationContext)) {
                MailCheckCoordinatorFactory.create(applicationContext).check()
            } else {
                MailCheckResult(success = false, failureKind = MonitoringFailureKind.DEVICE_OFFLINE)
            }
            val completedAt = System.currentTimeMillis()
            if (result.success) {
                MonitoringStateStore.update(status.recordSuccess(completedAt, nextAt, expectedGeneration))
            } else {
                val failure = status.recordFailure(completedAt, nextAt, settings.consecutiveFailureThreshold, expectedGeneration)
                MonitoringStateStore.update(failure.state)
                if (failure.shouldNotify) MonitoringFailureNotifier(applicationContext).post(result.failureKind ?: MonitoringFailureKind.SERVER_UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            val failure = status.recordFailure(System.currentTimeMillis(), nextAt, settings.consecutiveFailureThreshold, expectedGeneration)
            MonitoringStateStore.update(failure.state)
            if (failure.shouldNotify) MonitoringFailureNotifier(applicationContext).post(MonitoringFailureKind.SERVER_UNAVAILABLE)
        } finally {
            if (startupRebase) {
                MonitoringScheduler.enqueueRebasedScheduleIfCurrent(
                    applicationContext,
                    StartupReconcilePolicy.targetAfterCheck(startedAt, settings.pollIntervalMinutes),
                    generation,
                    settings.pollIntervalMinutes,
                )
            } else {
                rescheduleShortIfCurrent(settings.pollIntervalMinutes, generation, startedAt, scheduled)
            }
        }
        Result.success()
    }

    private suspend fun rescheduleShortIfCurrent(
        intervalMinutes: Int,
        generation: Long,
        startedAt: Long?,
        scheduled: Boolean = true,
    ) {
        if (!scheduled || startedAt == null || intervalMinutes >= MonitoringScheduler.MIN_PERIODIC_MINUTES) return
        val target = MonitoringExecutionPolicy.calculateNextTarget(startedAt, intervalMinutes)
        MonitoringScheduler.enqueueShortIfCurrent(applicationContext, target, generation)
    }
    private companion object { val executionMutex = Mutex() }
}
