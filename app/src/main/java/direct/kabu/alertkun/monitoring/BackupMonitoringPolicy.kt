package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.data.repository.MonitoringExecutionStatus

/** Pure conservative decisions, always evaluated using state read inside executionMutex. */
internal object BackupMonitoringPolicy {
    fun mayExecute(trigger: MailCheckTrigger, settingsEnabled: Boolean, statusEnabled: Boolean,
        currentGeneration: Long, workerGeneration: Long): Boolean = when (trigger) {
        MailCheckTrigger.MANUAL -> true
        MailCheckTrigger.FCM -> settingsEnabled && statusEnabled
        MailCheckTrigger.SCHEDULED_WORK, MailCheckTrigger.STARTUP -> settingsEnabled &&
            MonitoringExecutionPolicy.mayReschedule(statusEnabled, currentGeneration, workerGeneration)
    }

    fun intervalMinutes(userIntervalMinutes: Int): Int {
        require(userIntervalMinutes in 1..1500)
        return maxOf(userIntervalMinutes, 15)
    }

    /** Persisted due target is the anchor, rather than now minus a sliding 15 minutes. */
    fun windowStart(now: Long, target: Long?, intervalMinutes: Int?): Long? {
        if (target == null || target <= 0 || intervalMinutes == null || intervalMinutes !in 15..1500) return null
        val period = intervalMinutes * 60_000L
        if (now <= 0 || target - now > period) return null
        val due = if (now > target) target + ((now - target) / period) * period else target
        return (due - period).takeIf { it > 0 }
    }

    fun nextTarget(now: Long, target: Long?, intervalMinutes: Int): Long {
        val period = intervalMinutes * 60_000L
        if (target == null || target <= 0) return now + period
        if (target > now) return target + period
        return target + ((now - target) / period + 1) * period
    }

    fun shouldSkipBackup(now: Long, generation: Long, intervalMinutes: Int, state: MonitoringExecutionStatus): Boolean {
        if (!MonitoringExecutionPolicy.mayReschedule(state.monitoringEnabled, state.scheduleGeneration, generation)) return false
        if (state.backupIntervalMinutes != intervalMinutes || state.scheduleKind != MonitoringScheduleKind.PERIODIC.name) return false
        val start = windowStart(now, state.nextBackupAtEpochMillis, intervalMinutes) ?: return false
        return recentSuccess(state, MailCheckTrigger.FCM, generation, start, now)
    }

    /** Handles the inverse race: an already successful backup can fulfill this FCM interval. */
    fun shouldSkipFcmAfterBackup(now: Long, userIntervalMinutes: Int, state: MonitoringExecutionStatus): Boolean =
        state.monitoringEnabled && recentSuccess(state, MailCheckTrigger.SCHEDULED_WORK,
            state.scheduleGeneration, now - userIntervalMinutes * 60_000L, now)

    private fun recentSuccess(state: MonitoringExecutionStatus, trigger: MailCheckTrigger, generation: Long, start: Long, now: Long): Boolean {
        val success = state.lastSuccessfulCheckAtEpochMillis ?: return false
        return state.lastAttemptSucceeded == true && state.lastSuccessfulTrigger == trigger && state.lastSuccessfulGeneration == generation &&
            success in start..now && state.lastCheckCompletedAtEpochMillis == success &&
            state.lastCheckStartedAtEpochMillis != null && state.lastCheckStartedAtEpochMillis <= success
    }
}
