package direct.kabu.alertkun.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One bounded check or backup skip, always using the same process-wide execution mutex. */
class MailCheckWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val workerStarted = android.os.SystemClock.elapsedRealtime()
        val observedGeneration = inputData.getLong(MonitoringScheduler.KEY_GENERATION, -1L)
        val observedTrigger = MailCheckTrigger.fromInput(inputData.getString(MonitoringScheduler.KEY_TRIGGER),
            inputData.getBoolean(MonitoringScheduler.KEY_SCHEDULED, false), inputData.getBoolean(MonitoringScheduler.KEY_STARTUP_REBASE, false))
        var workerResult = "FAILURE"
        var checkResult = "NOT_STARTED"
        fun observe(event: String, vararg fields: Pair<String, Any?>) = MonitoringObservation.record(applicationContext, event,
            "workId" to id.toString(), "trigger" to observedTrigger.name, "inputGeneration" to observedGeneration, *fields)
        monitoringDebugLog(applicationContext, "worker waiting for execution mutex id=$id")
        observe("MUTEX_WAIT")
        try {
            return executionMutex.withLock {
                observe("MUTEX_ACQUIRED")
                // All settings and success attribution are read after acquiring this shared lock.
                val settings = SettingsRepository(applicationContext).settings.first()
                val status = MonitoringStatusRepository(applicationContext)
                val scheduled = inputData.getBoolean(MonitoringScheduler.KEY_SCHEDULED, false)
                val generation = inputData.getLong(MonitoringScheduler.KEY_GENERATION, -1L)
                val startupRebase = inputData.getBoolean(MonitoringScheduler.KEY_STARTUP_REBASE, false)
                val trigger = MailCheckTrigger.fromInput(inputData.getString(MonitoringScheduler.KEY_TRIGGER), scheduled, startupRebase)
                val execution = status.executionStatus()
                MonitoringObservation.generation = execution.scheduleGeneration
                observe("WORKER_START", "currentGeneration" to execution.scheduleGeneration, "runAttemptCount" to runAttemptCount,
                    "tags" to tags.sorted().joinToString(","),
                    "workKind" to if (tags.any { it.startsWith("backup_interval_") }) "PERIODIC" else "ONE_TIME")
                observe("WORKER_PRECHECK", "monitoringEnabled" to settings.monitoringEnabled, "statusEnabled" to execution.monitoringEnabled,
                    "currentGeneration" to execution.scheduleGeneration, "backupInterval" to BackupMonitoringPolicy.intervalMinutes(settings.pollIntervalMinutes),
                    "userInterval" to settings.pollIntervalMinutes, "nowMs" to System.currentTimeMillis())
                monitoringDebugLog(applicationContext, "worker start id=$id trigger=$trigger scheduled=$scheduled generation=$generation startup=$startupRebase attempt=$runAttemptCount")
                if (!BackupMonitoringPolicy.mayExecute(trigger, settings.monitoringEnabled, execution.monitoringEnabled,
                        execution.scheduleGeneration, generation)) {
                    monitoringDebugLog(applicationContext, "worker skip id=$id trigger=$trigger generation mismatch/monitoring OFF current=${execution.scheduleGeneration}")
                    observe("WORKER_SKIP", "skipReason" to if (!settings.monitoringEnabled || !execution.monitoringEnabled) "MONITORING_OFF" else "GENERATION_MISMATCH",
                        "monitoringEnabled" to settings.monitoringEnabled, "statusEnabled" to execution.monitoringEnabled,
                        "currentGeneration" to execution.scheduleGeneration)
                    workerResult = "SUCCESS"
                    checkResult = "SKIPPED"
                    return@withLock Result.success()
                }
                val now = System.currentTimeMillis()
                val backupInterval = BackupMonitoringPolicy.intervalMinutes(settings.pollIntervalMinutes)
                val expectedGeneration = execution.scheduleGeneration
                val selection = MailCheckCoordinatorFactory.select(applicationContext, settings.demoModeEnabled)
                if (selection.mode == direct.kabu.alertkun.mail.common.MailCheckMode.NO_ACCOUNT) {
                    monitoringDebugLog(applicationContext, "Mail check skipped: no configured mail account")
                    observe("WORKER_SKIP", "skipReason" to "NO_CONFIGURED_MAIL_ACCOUNT", "currentGeneration" to expectedGeneration)
                    // A skip is neither a successful mail check nor a failure. Keep existing scheduling semantics.
                    if (startupRebase) {
                        MonitoringScheduler.enqueueRebasedScheduleIfCurrent(applicationContext,
                            StartupReconcilePolicy.targetAfterCheck(now, settings.pollIntervalMinutes), generation, settings.pollIntervalMinutes)
                    } else if (trigger == MailCheckTrigger.SCHEDULED_WORK) {
                        status.advanceBackupWindow(now, generation, backupInterval)
                        MonitoringStateStore.update(status.current())
                    }
                    workerResult = "SUCCESS"
                    checkResult = "SKIPPED"
                    return@withLock Result.success()
                }
                if (trigger == MailCheckTrigger.SCHEDULED_WORK && BackupMonitoringPolicy.shouldSkipBackup(now, generation, backupInterval, execution)) {
                    observe("WORKER_SKIP", "skipReason" to "RECENT_FCM_SUCCESS", "currentGeneration" to execution.scheduleGeneration,
                        *SkipObservation.fields(now, generation, backupInterval, settings.pollIntervalMinutes, settings.monitoringEnabled, trigger, execution).toList().toTypedArray())
                    monitoringDebugLog(applicationContext, "scheduled backup skipped: successful FCM check already completed in current backup window id=$id windowStartMs=${BackupMonitoringPolicy.windowStart(now, execution.nextBackupAtEpochMillis, backupInterval)} successMs=${execution.lastSuccessfulCheckAtEpochMillis}")
                    status.advanceBackupWindow(now, generation, backupInterval)
                    MonitoringStateStore.update(status.current())
                    workerResult = "SUCCESS"
                    checkResult = "SKIPPED"
                    return@withLock Result.success()
                }
                if (trigger == MailCheckTrigger.FCM && BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, settings.pollIntervalMinutes, execution)) {
                    observe("WORKER_SKIP", "skipReason" to "RECENT_BACKUP_SUCCESS", "currentGeneration" to execution.scheduleGeneration,
                        *SkipObservation.fields(now, generation, backupInterval, settings.pollIntervalMinutes, settings.monitoringEnabled, trigger, execution).toList().toTypedArray())
                    monitoringDebugLog(applicationContext, "FCM check skipped: recent successful backup already fulfilled this interval id=$id")
                    workerResult = "SUCCESS"
                    checkResult = "SKIPPED"
                    return@withLock Result.success()
                }
                val startedAt = now
                val connectionObservation = MonitoringObservation.sink(applicationContext, id.toString(), trigger, expectedGeneration)
                observe("WORKER_EXECUTE", "generation" to expectedGeneration)
                monitoringDebugLog(applicationContext, "mail check start id=$id trigger=$trigger interval=${settings.pollIntervalMinutes} backupInterval=$backupInterval")
                status.recordCheckStarted(startedAt, expectedGeneration)
                val checkStarted = android.os.SystemClock.elapsedRealtime()
                observe("CHECK_START", "generation" to expectedGeneration)
                var completionAttempted = false
                suspend fun complete(result: MailCheckResult, completedAt: Long) {
                    // Account only the final poll outcome, never an individual read attempt or account.
                    completionAttempted = true
                    val completion = MailCheckCompletionHandler(status).complete(result, completedAt,
                        settings.consecutiveFailureThreshold, trigger, expectedGeneration)
                    MonitoringStateStore.update(completion.state)
                    // Display errors must not turn a completed success into failure or count failure twice.
                    runCatching {
                        MonitoringStatusNotifier(applicationContext).onCheckResult(completedAt, completion.display,
                            expectedGeneration, trigger, id.toString())
                    }.onFailure {
                        observe("MONITORING_NOTIFICATION_BLOCKED", "generation" to expectedGeneration,
                            "notificationId" to MonitoringStatusNotifier.NOTIFICATION_ID,
                            "channelId" to MonitoringStatusNotifier.CHANNEL_ID, "action" to "SKIP",
                            "notificationPhase" to "CHECK_RESULT", "resultState" to completion.display.observationState,
                            "reason" to "NOTIFICATION_ERROR")
                    }
                    if (result.success) MonitoringHealthReporter(applicationContext, status).report(completedAt, expectedGeneration)
                    else if (completion.shouldNotifyFailure) MonitoringFailureNotifier(applicationContext)
                        .post(settings.consecutiveFailureThreshold, completedAt, result.failureKind)
                }
                try {
                    val result = if (selection.mode == direct.kabu.alertkun.mail.common.MailCheckMode.DEMO) {
                        MailCheckCoordinatorFactory.create(applicationContext, selection, null, connectionObservation).check()
                    } else DefaultNetworkMonitor(applicationContext, connectionObservation).use { network ->
                        MailCheckCoordinatorFactory.create(applicationContext, selection, NetworkStabilization(network,
                            { monitoringDebugLog(applicationContext, it) }, observation = connectionObservation), connectionObservation).check()
                    }
                    val completedAt = System.currentTimeMillis()
                    checkResult = if (result.success) "SUCCESS" else "FAILURE"
                    observe(if (result.success) "CHECK_SUCCESS" else "CHECK_FAILURE", "generation" to expectedGeneration,
                        "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - checkStarted), "category" to result.failureKind?.name,
                        "exceptionClass" to result.error?.javaClass?.simpleName)
                    monitoringDebugLog(applicationContext, "mail check end id=$id trigger=$trigger success=${result.success} failure=${result.failureKind} checked=${result.checkedCount} received=${result.receivedCount}")
                    complete(result, completedAt)
                } catch (cancelled: CancellationException) {
                    checkResult = "CANCELLED"
                    observe("CHECK_CANCELLED", "generation" to expectedGeneration,
                        "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - checkStarted))
                    withContext(NonCancellable) { status.recordHealthInterruption(System.currentTimeMillis()) }
                    monitoringDebugLog(applicationContext, "worker cancelled id=$id stopped=$isStopped")
                    throw cancelled
                } catch (error: Exception) {
                    // A persistence/reporting exception after accounting is not another failed MailCheck.
                    if (completionAttempted) throw error
                    checkResult = "FAILURE"
                    observe("CHECK_FAILURE", "generation" to expectedGeneration,
                        "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - checkStarted),
                        "category" to direct.kabu.alertkun.mail.common.MailConnectionSettings.classify(error).name,
                        "exceptionClass" to error.javaClass.simpleName)
                    monitoringDebugLog(applicationContext, "worker exception id=$id type=${error.javaClass.simpleName}")
                    val completedAt = System.currentTimeMillis()
                    complete(MailCheckResult(false, error = error,
                        failureKind = direct.kabu.alertkun.mail.common.MailConnectionSettings.classify(error)), completedAt)
                } finally {
                    LocalMailCleanup(SettingsRepository(applicationContext), { policy, cleanupAt ->
                        direct.kabu.alertkun.data.database.AlertKunDatabase.getInstance(applicationContext)
                            .receivedMailDao().purgeLocalMail(policy, cleanupAt)
                    }, connectionObservation).run(startedAt, System.currentTimeMillis())
                    if (startupRebase) {
                        MonitoringScheduler.enqueueRebasedScheduleIfCurrent(applicationContext,
                            StartupReconcilePolicy.targetAfterCheck(startedAt, settings.pollIntervalMinutes), generation, settings.pollIntervalMinutes)
                    } else if (trigger == MailCheckTrigger.SCHEDULED_WORK) {
                        status.advanceBackupWindow(System.currentTimeMillis(), generation, backupInterval)
                        MonitoringStateStore.update(status.current())
                    }
                }
                monitoringDebugLog(applicationContext, "worker end id=$id trigger=$trigger stopped=$isStopped")
                workerResult = "SUCCESS"
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            workerResult = "CANCELLED"
            throw cancelled
        } finally {
            observe("WORKER_END", "result" to workerResult, "checkResult" to checkResult,
                "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - workerStarted), "stopped" to isStopped)
        }
    }

    internal companion object { val executionMutex = Mutex() }
}
