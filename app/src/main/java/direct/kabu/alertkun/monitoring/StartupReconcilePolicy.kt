package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.model.MonitoringSettings

internal enum class StartupReconcileAction {
    NONE,
    REBASE_WITH_IMMEDIATE_CHECK,
    RECONCILE_ONLY,
}

/** Pure startup decisions; scheduling side effects remain in [MonitoringScheduler]. */
internal object StartupReconcilePolicy {
    fun actionFor(settings: MonitoringSettings): StartupReconcileAction = when {
        !settings.monitoringEnabled -> StartupReconcileAction.NONE
        settings.checkImmediatelyOnAppStart -> StartupReconcileAction.REBASE_WITH_IMMEDIATE_CHECK
        else -> StartupReconcileAction.RECONCILE_ONLY
    }

    fun targetAfterCheck(startedAtEpochMillis: Long, intervalMinutes: Int): Long =
        MonitoringExecutionPolicy.calculateNextTarget(startedAtEpochMillis, intervalMinutes)

    fun targetWithoutImmediateCheck(nowEpochMillis: Long, intervalMinutes: Int): Long =
        MonitoringExecutionPolicy.calculateNextTarget(nowEpochMillis, intervalMinutes)

    fun shouldKeepExistingSchedule(
        nowEpochMillis: Long,
        nextScheduledAtEpochMillis: Long?,
        savedScheduleKind: String?,
        expectedScheduleKind: MonitoringScheduleKind,
        normalWorkExists: Boolean,
    ): Boolean =
        nextScheduledAtEpochMillis != null &&
            nextScheduledAtEpochMillis > nowEpochMillis &&
            savedScheduleKind == expectedScheduleKind.name &&
            normalWorkExists
}

internal class StartupReconcileGate {
    private var consumed = false

    fun consumeOnce(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}
