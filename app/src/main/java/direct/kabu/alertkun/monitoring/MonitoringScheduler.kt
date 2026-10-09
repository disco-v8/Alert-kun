package direct.kabu.alertkun.monitoring

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import direct.kabu.alertkun.data.repository.MonitoringStatusRepository
import direct.kabu.alertkun.model.MonitoringSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ONE_TIME is retained only to recognize and migrate persisted legacy schedules.
enum class MonitoringScheduleKind { ONE_TIME, PERIODIC }

object MonitoringScheduler {
    internal const val PERIODIC_WORK_NAME = "alertkun_mail_check_periodic"
    internal const val SHORT_WORK_NAME = "alertkun_mail_check_short"
    internal const val NOW_WORK_NAME = "alertkun_mail_check_now"
    internal const val FCM_WORK_NAME = "alertkun_mail_check_fcm"
    internal const val STARTUP_WORK_NAME = "alertkun_mail_check_startup"
    internal const val KEY_SCHEDULED = "scheduled"
    internal const val KEY_GENERATION = "generation"
    internal const val KEY_STARTUP_REBASE = "startup_rebase"
    internal const val KEY_TRIGGER = "trigger"
    private val scheduleMutationMutex = Mutex()
    private val startupMutex = Mutex()
    private val startupReconcileGate = StartupReconcileGate()
    private val clockBaselineTracker = ClockBaselineTracker()

    fun backupIntervalMinutes(intervalMinutes: Int): Int = BackupMonitoringPolicy.intervalMinutes(intervalMinutes)
    fun kindFor(intervalMinutes: Int): MonitoringScheduleKind {
        backupIntervalMinutes(intervalMinutes)
        return MonitoringScheduleKind.PERIODIC
    }

    internal fun initialNextScheduledAt(nowEpochMillis: Long, intervalMinutes: Int): Long =
        MonitoringExecutionPolicy.calculateNextTarget(nowEpochMillis, backupIntervalMinutes(intervalMinutes))
    suspend fun start(context: Context, intervalMinutes: Int) = reschedule(context, intervalMinutes)

    suspend fun reconcileOnAppStart(context: Context, settings: MonitoringSettings) {
        startupMutex.withLock {
            if (!startupReconcileGate.consumeOnce()) {
                monitoringDebugLog(context, "startup reconcile skipped gate consumed")
                return
            }
            monitoringDebugLog(context, "startup reconcile action=${StartupReconcilePolicy.actionFor(settings)} interval=${settings.pollIntervalMinutes}")
            clockBaselineTracker.initialize(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            when (StartupReconcilePolicy.actionFor(settings)) {
                StartupReconcileAction.NONE -> Unit
                StartupReconcileAction.REBASE_WITH_IMMEDIATE_CHECK -> rebaseForStartupCheck(context, settings.pollIntervalMinutes)
                StartupReconcileAction.RECONCILE_ONLY -> reconcileWithoutStartupCheck(context, settings.pollIntervalMinutes)
            }
        }
    }

    suspend fun handleTimeChanged(context: Context, wallClockMillis: Long = System.currentTimeMillis(), elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()) {
        if (!clockBaselineTracker.recordTimeChanged(wallClockMillis, elapsedRealtimeMillis)) return
        val settings = direct.kabu.alertkun.data.repository.SettingsRepository(context).settings.first()
        if (settings.monitoringEnabled) rebaseWithoutImmediateCheck(context, settings.pollIntervalMinutes, wallClockMillis)
    }
    fun handleTimezoneChanged() = MonitoringTimeChangeStore.notifyTimezoneChanged()

    /** Preserve a valid backup; migrate legacy short schedules or missing metadata. */
    suspend fun sync(context: Context, intervalMinutes: Int) = reconcileWithoutStartupCheck(context, intervalMinutes)

    suspend fun reschedule(context: Context, intervalMinutes: Int) =
        rebaseWithoutImmediateCheck(context, intervalMinutes, System.currentTimeMillis())

    suspend fun stop(context: Context): List<androidx.work.Operation> =
        scheduleMutationMutex.withLock {
            val status = MonitoringStatusRepository(context)
            val generation = status.changeSchedule(false, null)
            runCatching { MonitoringStatusNotifier(context).cancel(generation) }
            val manager = WorkManager.getInstance(context)
            val operations = listOf(PERIODIC_WORK_NAME, SHORT_WORK_NAME, NOW_WORK_NAME, FCM_WORK_NAME, STARTUP_WORK_NAME)
                .map(manager::cancelUniqueWork)
            MonitoringStateStore.update(status.current())
            operations
        }

    fun runNow(context: Context, trigger: MailCheckTrigger = MailCheckTrigger.MANUAL, messageIdHash: String? = null): androidx.work.Operation {
        require(trigger == MailCheckTrigger.MANUAL || trigger == MailCheckTrigger.FCM)
        // A manual check must never swallow an FCM request and misattribute its result.
        val name = if (trigger == MailCheckTrigger.FCM) FCM_WORK_NAME else NOW_WORK_NAME
        val request = OneTimeWorkRequestBuilder<MailCheckWorker>().addTag("mail_check_${trigger.name}").setInputData(
            workDataOf(KEY_SCHEDULED to false, KEY_TRIGGER to trigger.name)).build()
        monitoringDebugLog(context, "runNow enqueue trigger=$trigger id=${request.id}")
        if (trigger == MailCheckTrigger.FCM) MonitoringObservation.record(context, "FCM_DISPATCH_REQUEST",
            "trigger" to trigger.name, "generation" to MonitoringObservation.generation,
            "requestedWorkId" to request.id.toString(), "messageIdHash" to messageIdHash)
        MonitoringObservation.record(context, "WORK_ENQUEUE_REQUEST", "trigger" to trigger.name,
            "generation" to MonitoringObservation.generation, "requestedWorkId" to request.id.toString(),
            "uniqueName" to name, "policy" to "KEEP", "messageIdHash" to messageIdHash)
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        MonitoringObservation.enqueueCommitted(context, operation, request.id.toString(), name, trigger, "KEEP")
        return operation
    }

    internal suspend fun enqueueRebasedScheduleIfCurrent(context: Context, targetAtEpochMillis: Long, generation: Long, intervalMinutes: Int): Boolean = scheduleMutationMutex.withLock {
        val status = MonitoringStatusRepository(context)
        val backup = backupIntervalMinutes(intervalMinutes)
        if (!status.setBackupScheduleIfCurrent(targetAtEpochMillis, generation, backup)) return@withLock false
        WorkManager.getInstance(context).cancelUniqueWork(SHORT_WORK_NAME)
        enqueuePeriodic(context, targetAtEpochMillis, generation, backup)
        MonitoringStateStore.update(status.current())
        true
    }

    private suspend fun rebaseForStartupCheck(context: Context, intervalMinutes: Int) {
        scheduleMutationMutex.withLock {
            val manager = WorkManager.getInstance(context)
            val status = MonitoringStatusRepository(context)
            val generation = status.changeSchedule(true, null, MonitoringScheduleKind.PERIODIC.name, backupIntervalMinutes(intervalMinutes))
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(SHORT_WORK_NAME)
            val request = OneTimeWorkRequestBuilder<MailCheckWorker>().setInputData(workDataOf(
                    KEY_SCHEDULED to true, KEY_GENERATION to generation,
                    KEY_STARTUP_REBASE to true, KEY_TRIGGER to MailCheckTrigger.STARTUP.name)).build()
            MonitoringObservation.record(context, "WORK_ENQUEUE_REQUEST", "trigger" to "STARTUP",
                "generation" to generation, "requestedWorkId" to request.id.toString(),
                "uniqueName" to STARTUP_WORK_NAME, "policy" to "REPLACE")
            val operation = manager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            MonitoringObservation.enqueueCommitted(context, operation, request.id.toString(), STARTUP_WORK_NAME, MailCheckTrigger.STARTUP, "REPLACE")
            MonitoringStateStore.update(status.current())
        }
    }

    private suspend fun reconcileWithoutStartupCheck(context: Context, intervalMinutes: Int) {
        val status = MonitoringStatusRepository(context)
        val execution = status.executionStatus()
        val state = status.current()
        val now = System.currentTimeMillis()
        if (execution.monitoringEnabled && execution.backupIntervalMinutes == backupIntervalMinutes(intervalMinutes) &&
            StartupReconcilePolicy.shouldKeepExistingSchedule(now, execution.nextBackupAtEpochMillis,
                execution.scheduleKind, MonitoringScheduleKind.PERIODIC,
                normalWorkExists(context, execution.scheduleGeneration, backupIntervalMinutes(intervalMinutes)))) {
            MonitoringStateStore.update(state)
            return
        }
        rebaseWithoutImmediateCheck(context, intervalMinutes, now)
    }

    private suspend fun rebaseWithoutImmediateCheck(context: Context, intervalMinutes: Int, now: Long): androidx.work.Operation =
        scheduleMutationMutex.withLock {
            val manager = WorkManager.getInstance(context)
            val status = MonitoringStatusRepository(context)
            val backup = backupIntervalMinutes(intervalMinutes)
            val target = initialNextScheduledAt(now, intervalMinutes)
            val generation = status.changeSchedule(true, target, MonitoringScheduleKind.PERIODIC.name, backup)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(SHORT_WORK_NAME)
            manager.cancelUniqueWork(STARTUP_WORK_NAME)
            val operation = enqueuePeriodic(context, target, generation, backup)
            MonitoringStateStore.update(status.current())
            operation
        }

    private suspend fun normalWorkExists(context: Context, generation: Long, interval: Int): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(PERIODIC_WORK_NAME).get().any {
            !it.state.isFinished && "backup_generation_$generation" in it.tags && "backup_interval_$interval" in it.tags
        }
    }

    private fun enqueuePeriodic(context: Context, target: Long, generation: Long, backupInterval: Int): androidx.work.Operation {
        val request = PeriodicWorkRequestBuilder<MailCheckWorker>(backupInterval.toLong(), TimeUnit.MINUTES)
            .addTag("backup_generation_$generation").addTag("backup_interval_$backupInterval")
            .setInitialDelay(MonitoringExecutionPolicy.delayMillis(target, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SCHEDULED to true, KEY_GENERATION to generation, KEY_TRIGGER to MailCheckTrigger.SCHEDULED_WORK.name))
            .build()
        monitoringDebugLog(context, "backup enqueue id=${request.id} targetMs=$target interval=$backupInterval generation=$generation")
        MonitoringObservation.record(context, "WORK_ENQUEUE_REQUEST", "trigger" to "SCHEDULED_WORK",
            "generation" to generation, "requestedWorkId" to request.id.toString(), "uniqueName" to PERIODIC_WORK_NAME,
            "policy" to "UPDATE", "backupTargetMs" to target, "backupInterval" to backupInterval)
        val operation = WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        MonitoringObservation.enqueueCommitted(context, operation, request.id.toString(), PERIODIC_WORK_NAME, MailCheckTrigger.SCHEDULED_WORK, "UPDATE")
        return operation
    }
}
