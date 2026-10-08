package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.data.repository.MonitoringExecutionStatus
import org.junit.Assert.*
import org.junit.Test

class BackupMonitoringPolicyTest {
    private val now = 60 * 60_000L
    private val generation = 7L
    private fun state() = MonitoringExecutionStatus(true, generation, "PERIODIC", now - 2_000, now - 1_000,
        now - 1_000, MailCheckTrigger.FCM, generation, now, 15, true)

    @Test fun backupIntervalsRespectUserIntervalAndFifteenMinuteMinimum() {
        mapOf(1 to 15, 3 to 15, 5 to 15, 10 to 15, 14 to 15, 15 to 15, 16 to 16, 30 to 30, 60 to 60, 1500 to 1500)
            .forEach { (user, backup) -> assertEquals(backup, MonitoringScheduler.backupIntervalMinutes(user)) }
    }

    @Test fun sameWindowAndImmediatelyPreviousFcmSuccessSkipBackup() {
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state()))
        val boundary = now - 15 * 60_000L
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state().copy(
            lastCheckStartedAtEpochMillis = boundary - 1, lastCheckCompletedAtEpochMillis = boundary,
            lastSuccessfulCheckAtEpochMillis = boundary)))
    }

    @Test fun receivedButFailedOrCancelledFcmDoesNotSkipBackup() {
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state().copy(lastCheckCompletedAtEpochMillis = now)))
        // In-flight/cancelled work must not reuse the success of an earlier check.
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state().copy(lastCheckStartedAtEpochMillis = now)))
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state().copy(lastAttemptSucceeded = false)))
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, state().copy(lastAttemptSucceeded = null)))
    }

    @Test fun absentOldFutureAndOtherTriggerSuccessDoNotSkipBackup() {
        val invalid = listOf(state().copy(lastSuccessfulCheckAtEpochMillis = null),
            state().copy(lastSuccessfulCheckAtEpochMillis = now - 15 * 60_000L - 1),
            state().copy(lastSuccessfulCheckAtEpochMillis = now + 1),
            state().copy(lastSuccessfulTrigger = null)) +
            listOf(MailCheckTrigger.MANUAL, MailCheckTrigger.STARTUP, MailCheckTrigger.SCHEDULED_WORK)
                .map { state().copy(lastSuccessfulTrigger = it) }
        invalid.forEach { assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, it)) }
    }

    @Test fun offWrongGenerationAndIncompleteScheduleAreNeverEvidenceForBackupSkip() {
        listOf(state().copy(monitoringEnabled = false), state().copy(scheduleGeneration = generation + 1),
            state().copy(lastSuccessfulGeneration = generation - 1), state().copy(lastSuccessfulGeneration = null),
            state().copy(nextBackupAtEpochMillis = null), state().copy(backupIntervalMinutes = null),
            state().copy(backupIntervalMinutes = 30), state().copy(scheduleKind = "ONE_TIME"),
            state().copy(nextBackupAtEpochMillis = now + 16 * 60_000L))
            .forEach { assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now, generation, 15, it)) }
        assertFalse(MonitoringExecutionPolicy.mayReschedule(false, generation, generation))
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, generation, generation - 1))
    }

    @Test fun lateWorkersUseAnchoredWindowRatherThanNowMinusInterval() {
        val late = now + 4 * 60_000L
        val success = now - 14 * 60_000L // Inside the anchored period, outside a sliding 15 minutes.
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(late, generation, 15, state().copy(
            lastCheckStartedAtEpochMillis = success - 1, lastCheckCompletedAtEpochMillis = success,
            lastSuccessfulCheckAtEpochMillis = success)))
        assertEquals(now - 15 * 60_000L, BackupMonitoringPolicy.windowStart(late, now, 15))
        assertEquals(now, BackupMonitoringPolicy.windowStart(now + 15 * 60_000L, now, 15))
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(now + 15 * 60_000L, generation, 15, state()))
        assertEquals(now + 30 * 60_000L, BackupMonitoringPolicy.nextTarget(now + 16 * 60_000L, now, 15))
    }

    @Test fun inverseRaceReusesRecentSuccessfulBackupButDoesNotMislabelItAsFcm() {
        val backup = state().copy(lastSuccessfulTrigger = MailCheckTrigger.SCHEDULED_WORK)
        assertTrue(BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, 5, backup))
        assertEquals(MailCheckTrigger.SCHEDULED_WORK, backup.lastSuccessfulTrigger)
        assertFalse(BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now + 5 * 60_000L, 5, backup))
        assertFalse(BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, 5, backup.copy(monitoringEnabled = false)))
        assertFalse(BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, 5, state().copy(lastSuccessfulTrigger = MailCheckTrigger.MANUAL)))
    }

    @Test fun legacyInputIsClassifiedAndOnlyExplicitFcmInputBecomesFcm() {
        assertEquals(MailCheckTrigger.FCM, MailCheckTrigger.fromInput("FCM", false, false))
        assertEquals(MailCheckTrigger.MANUAL, MailCheckTrigger.fromInput(null, false, false))
        assertEquals(MailCheckTrigger.MANUAL, MailCheckTrigger.fromInput("UNKNOWN", false, false))
        assertEquals(MailCheckTrigger.SCHEDULED_WORK, MailCheckTrigger.fromInput("FCM", true, false))
        assertEquals(MailCheckTrigger.STARTUP, MailCheckTrigger.fromInput(null, true, true))
    }

    @Test fun monitoringOffBlocksFcmAndScheduledButPreservesExplicitManualChecks() {
        for (trigger in listOf(MailCheckTrigger.FCM, MailCheckTrigger.SCHEDULED_WORK, MailCheckTrigger.STARTUP)) {
            assertFalse(BackupMonitoringPolicy.mayExecute(trigger, false, false, generation, generation))
            assertFalse(BackupMonitoringPolicy.mayExecute(trigger, true, false, generation, generation))
            assertFalse(BackupMonitoringPolicy.mayExecute(trigger, false, true, generation, generation))
            assertTrue(BackupMonitoringPolicy.mayExecute(trigger, true, true, generation, generation))
        }
        assertTrue(BackupMonitoringPolicy.mayExecute(MailCheckTrigger.MANUAL, false, false, generation, -1))
        assertFalse(BackupMonitoringPolicy.mayExecute(MailCheckTrigger.SCHEDULED_WORK, true, true, generation, generation - 1))
        assertFalse(BackupMonitoringPolicy.mayExecute(MailCheckTrigger.STARTUP, true, true, generation, generation - 1))
    }
}
