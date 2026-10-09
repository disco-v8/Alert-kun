package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.data.repository.MonitoringExecutionStatus

/** Copies the already-read decision inputs. Never participates in the policy decision. */
internal object SkipObservation {
    fun fields(now: Long, inputGeneration: Long, backupInterval: Int, userInterval: Int,
        settingsEnabled: Boolean, trigger: MailCheckTrigger, state: MonitoringExecutionStatus): Map<String, Any?> {
        val backup = trigger == MailCheckTrigger.SCHEDULED_WORK
        val start = if (backup) BackupMonitoringPolicy.windowStart(now, state.nextBackupAtEpochMillis, backupInterval)
            else now - userInterval * 60_000L
        val expected = if (backup) inputGeneration else state.scheduleGeneration
        val success = state.lastSuccessfulCheckAtEpochMillis
        return mapOf(
            "nowMs" to now, "lastSuccessMs" to success, "lastSuccessTrigger" to state.lastSuccessfulTrigger?.name,
            "lastSuccessGeneration" to state.lastSuccessfulGeneration, "backupTargetMs" to state.nextBackupAtEpochMillis,
            "windowStartMs" to start, "windowEndMs" to now, "lastStartedMs" to state.lastCheckStartedAtEpochMillis,
            "lastCompletedMs" to state.lastCheckCompletedAtEpochMillis, "lastAttemptSucceeded" to state.lastAttemptSucceeded,
            "enabledCondition" to (settingsEnabled && state.monitoringEnabled),
            "generationCondition" to (!backup || state.scheduleGeneration == inputGeneration),
            "metadataCondition" to (!backup || (state.backupIntervalMinutes == backupInterval && state.scheduleKind == "PERIODIC")),
            "triggerCondition" to (state.lastSuccessfulTrigger == if (backup) MailCheckTrigger.FCM else MailCheckTrigger.SCHEDULED_WORK),
            "successGenerationCondition" to (state.lastSuccessfulGeneration == expected),
            "windowCondition" to (success != null && start != null && success in start..now),
            "attemptCondition" to (state.lastAttemptSucceeded == true),
            "completedCondition" to (success != null && state.lastCheckCompletedAtEpochMillis == success),
            "startedCondition" to (success != null && state.lastCheckStartedAtEpochMillis != null && state.lastCheckStartedAtEpochMillis <= success),
        )
    }
}
