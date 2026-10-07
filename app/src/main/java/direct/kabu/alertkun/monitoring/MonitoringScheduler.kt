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

enum class MonitoringScheduleKind { ONE_TIME, PERIODIC }

object MonitoringScheduler {
    internal const val PERIODIC_WORK_NAME = "alertkun_mail_check_periodic"
    internal const val SHORT_WORK_NAME = "alertkun_mail_check_short"
    internal const val NOW_WORK_NAME = "alertkun_mail_check_now"
    internal const val STARTUP_WORK_NAME = "alertkun_mail_check_startup"
    internal const val KEY_SCHEDULED = "scheduled"
    internal const val KEY_GENERATION = "generation"
    internal const val KEY_STARTUP_REBASE = "startup_rebase"
    private val scheduleMutationMutex = Mutex()
    private val startupMutex = Mutex()
    private val startupReconcileGate = StartupReconcileGate()
    private val clockBaselineTracker = ClockBaselineTracker()

    fun kindFor(intervalMinutes: Int): MonitoringScheduleKind {
        require(intervalMinutes in 1..1500)
        return if (intervalMinutes >= MIN_PERIODIC_MINUTES) MonitoringScheduleKind.PERIODIC else MonitoringScheduleKind.ONE_TIME
    }

    /** A settings change rebases both short and periodic schedules from the change time. */
    internal fun initialNextScheduledAt(nowEpochMillis: Long, intervalMinutes: Int): Long =
        MonitoringExecutionPolicy.calculateNextTarget(nowEpochMillis, intervalMinutes)
    suspend fun start(context: Context, intervalMinutes: Int) = reschedule(context, intervalMinutes)

    /** Reconciles exactly once per app process; Activity recreation cannot enqueue another startup check. */
    suspend fun reconcileOnAppStart(context: Context, settings: MonitoringSettings) {
        startupMutex.withLock {
            if (!startupReconcileGate.consumeOnce()) return
            clockBaselineTracker.initialize(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            when (StartupReconcilePolicy.actionFor(settings)) {
                StartupReconcileAction.NONE -> Unit
                StartupReconcileAction.REBASE_WITH_IMMEDIATE_CHECK -> rebaseForStartupCheck(context, settings.pollIntervalMinutes)
                StartupReconcileAction.RECONCILE_ONLY -> reconcileWithoutStartupCheck(context, settings.pollIntervalMinutes)
            }
        }
    }

    suspend fun handleTimeChanged(
        context: Context,
        wallClockMillis: Long = System.currentTimeMillis(),
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        if (!clockBaselineTracker.recordTimeChanged(wallClockMillis, elapsedRealtimeMillis)) return
        val settings = direct.kabu.alertkun.data.repository.SettingsRepository(context).settings.first()
        if (settings.monitoringEnabled) rebaseWithoutImmediateCheck(context, settings.pollIntervalMinutes, wallClockMillis)
    }

    fun handleTimezoneChanged() = MonitoringTimeChangeStore.notifyTimezoneChanged()
    /** Restores missing work without moving an already scheduled target. */
    suspend fun sync(context: Context, intervalMinutes: Int) {
        val status = MonitoringStatusRepository(context)
        if (!status.executionStatus().monitoringEnabled) {
            reschedule(context, intervalMinutes)
            return
        }
        val generation = status.executionStatus().scheduleGeneration
        when (kindFor(intervalMinutes)) {
            MonitoringScheduleKind.PERIODIC -> WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MailCheckWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES).setInputData(scheduledData(generation)).build(),
            )
            MonitoringScheduleKind.ONE_TIME -> WorkManager.getInstance(context).enqueueUniqueWork(
                SHORT_WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MailCheckWorker>().setInputData(scheduledData(generation)).build(),
            )
        }
        MonitoringStateStore.update(status.current())
    }
    suspend fun reschedule(context: Context, intervalMinutes: Int) {
        scheduleMutationMutex.withLock {
            val manager = WorkManager.getInstance(context)
            val status = MonitoringStatusRepository(context)
            val now = System.currentTimeMillis()
            val target = initialNextScheduledAt(now, intervalMinutes)
            val generation = status.changeSchedule(true, target, kindFor(intervalMinutes).name)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(SHORT_WORK_NAME)
            manager.cancelUniqueWork(STARTUP_WORK_NAME)
            when (kindFor(intervalMinutes)) {
                MonitoringScheduleKind.PERIODIC -> enqueuePeriodic(context, target, generation, intervalMinutes)
                MonitoringScheduleKind.ONE_TIME -> enqueueShort(context, target, generation)
            }
            MonitoringStateStore.update(status.current())
        }
    }
    suspend fun stop(context: Context) {
        scheduleMutationMutex.withLock {
            val status = MonitoringStatusRepository(context)
            status.changeSchedule(false, null)
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME); cancelUniqueWork(SHORT_WORK_NAME); cancelUniqueWork(NOW_WORK_NAME); cancelUniqueWork(STARTUP_WORK_NAME)
            }
            MonitoringStateStore.update(status.current())
        }
    }
    fun runNow(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(
        NOW_WORK_NAME, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<MailCheckWorker>().setInputData(workDataOf(KEY_SCHEDULED to false)).build(),
    )
    internal fun enqueueShort(context: Context, targetAtEpochMillis: Long, generation: Long) = WorkManager.getInstance(context).enqueueUniqueWork(
        SHORT_WORK_NAME, ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<MailCheckWorker>()
            .setInitialDelay(MonitoringExecutionPolicy.delayMillis(targetAtEpochMillis, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setInputData(scheduledData(generation)).build(),
    )
    internal suspend fun enqueueShortIfCurrent(context: Context, targetAtEpochMillis: Long, generation: Long): Boolean = scheduleMutationMutex.withLock {
        val status = MonitoringStatusRepository(context)
        val current = status.executionStatus()
        if (!MonitoringExecutionPolicy.mayReschedule(current.monitoringEnabled, current.scheduleGeneration, generation)) return@withLock false
        status.setNextScheduled(targetAtEpochMillis)
        enqueueShort(context, targetAtEpochMillis, generation)
        true
    }

    /** Registers the normal schedule after a startup check has established its new cycle origin. */
    internal suspend fun enqueueRebasedScheduleIfCurrent(
        context: Context,
        targetAtEpochMillis: Long,
        generation: Long,
        intervalMinutes: Int,
    ): Boolean = scheduleMutationMutex.withLock {
        val status = MonitoringStatusRepository(context)
        val current = status.executionStatus()
        if (!MonitoringExecutionPolicy.mayReschedule(current.monitoringEnabled, current.scheduleGeneration, generation)) return@withLock false
        val manager = WorkManager.getInstance(context)
        status.setNextScheduled(targetAtEpochMillis)
        when (kindFor(intervalMinutes)) {
            MonitoringScheduleKind.PERIODIC -> {
                manager.cancelUniqueWork(SHORT_WORK_NAME)
                enqueuePeriodic(context, targetAtEpochMillis, generation, intervalMinutes)
            }
            MonitoringScheduleKind.ONE_TIME -> {
                manager.cancelUniqueWork(PERIODIC_WORK_NAME)
                enqueueShort(context, targetAtEpochMillis, generation)
            }
        }
        MonitoringStateStore.update(status.current())
        true
    }

    private suspend fun rebaseForStartupCheck(context: Context, intervalMinutes: Int) {
        scheduleMutationMutex.withLock {
            val manager = WorkManager.getInstance(context)
            val status = MonitoringStatusRepository(context)
            val generation = status.changeSchedule(true, null, kindFor(intervalMinutes).name)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(SHORT_WORK_NAME)
            manager.enqueueUniqueWork(
                STARTUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MailCheckWorker>()
                    .setInputData(startupData(generation))
                    .build(),
            )
            MonitoringStateStore.update(status.current())
        }
    }

    private suspend fun reconcileWithoutStartupCheck(context: Context, intervalMinutes: Int) {
        val status = MonitoringStatusRepository(context)
        val state = status.current()
        val execution = status.executionStatus()
        val kind = kindFor(intervalMinutes)
        val now = System.currentTimeMillis()
        if (StartupReconcilePolicy.shouldKeepExistingSchedule(
                now,
                state.nextScheduledCheckAtEpochMillis,
                execution.scheduleKind,
                kind,
                normalWorkExists(context, kind),
            )
        ) {
            MonitoringStateStore.update(state)
            return
        }
        rebaseWithoutImmediateCheck(context, intervalMinutes, now)
    }

    private suspend fun rebaseWithoutImmediateCheck(context: Context, intervalMinutes: Int, now: Long = System.currentTimeMillis()) {
        scheduleMutationMutex.withLock {
            val manager = WorkManager.getInstance(context)
            val status = MonitoringStatusRepository(context)
            val target = StartupReconcilePolicy.targetWithoutImmediateCheck(now, intervalMinutes)
            val generation = status.changeSchedule(true, target, kindFor(intervalMinutes).name)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(SHORT_WORK_NAME)
            manager.cancelUniqueWork(STARTUP_WORK_NAME)
            when (kindFor(intervalMinutes)) {
                MonitoringScheduleKind.PERIODIC -> enqueuePeriodic(context, target, generation, intervalMinutes)
                MonitoringScheduleKind.ONE_TIME -> enqueueShort(context, target, generation)
            }
            MonitoringStateStore.update(status.current())
        }
    }

    private suspend fun normalWorkExists(context: Context, kind: MonitoringScheduleKind): Boolean = withContext(Dispatchers.IO) {
        val name = if (kind == MonitoringScheduleKind.PERIODIC) PERIODIC_WORK_NAME else SHORT_WORK_NAME
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }
    }

    private fun enqueuePeriodic(context: Context, targetAtEpochMillis: Long, generation: Long, intervalMinutes: Int) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<MailCheckWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setInitialDelay(MonitoringExecutionPolicy.delayMillis(targetAtEpochMillis, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setInputData(scheduledData(generation))
            .build(),
    )

    private fun scheduledData(generation: Long) = workDataOf(KEY_SCHEDULED to true, KEY_GENERATION to generation)
    private fun startupData(generation: Long) = workDataOf(
        KEY_SCHEDULED to true,
        KEY_GENERATION to generation,
        KEY_STARTUP_REBASE to true,
    )
    const val MIN_PERIODIC_MINUTES = 15
}
