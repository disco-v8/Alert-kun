package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.data.repository.MonitoringExecutionStatus
import org.junit.Assert.*
import org.junit.Test

class SkipObservationTest {
    private val now = 3_600_000L
    private fun state() = MonitoringExecutionStatus(true, 7, "PERIODIC", now - 2_000, now - 1_000,
        now - 1_000, MailCheckTrigger.FCM, 7, now, 15, true)

    @Test fun successfulFcmSkipIncludesEveryDecisionInput() {
        val state = state()
        val fields = SkipObservation.fields(now, 7, 15, 5, true, MailCheckTrigger.SCHEDULED_WORK, state)
        assertTrue(BackupMonitoringPolicy.shouldSkipBackup(now, 7, 15, state))
        assertEquals(now - 900_000, fields["windowStartMs"])
        assertEquals(now, fields["windowEndMs"])
        assertEquals("FCM", fields["lastSuccessTrigger"])
        assertEquals(7L, fields["lastSuccessGeneration"])
        assertTrue(fields.filterKeys { it.endsWith("Condition") }.values.all { it == true })
    }

    @Test fun delayedWindowRejectsStaleSuccessInBothPolicyAndObservation() {
        val later = now + 4 * 60 * 60_000
        val fields = SkipObservation.fields(later, 7, 15, 5, true, MailCheckTrigger.SCHEDULED_WORK, state())
        assertFalse(BackupMonitoringPolicy.shouldSkipBackup(later, 7, 15, state()))
        assertEquals(false, fields["windowCondition"])
        assertEquals(later - 900_000, fields["windowStartMs"])
    }

    @Test fun inverseRaceUsesUserIntervalAndBackupTrigger() {
        val state = state().copy(lastSuccessfulTrigger = MailCheckTrigger.SCHEDULED_WORK)
        val fields = SkipObservation.fields(now, -1, 15, 5, true, MailCheckTrigger.FCM, state)
        assertTrue(BackupMonitoringPolicy.shouldSkipFcmAfterBackup(now, 5, state))
        assertEquals(now - 300_000, fields["windowStartMs"])
        assertEquals("SCHEDULED_WORK", fields["lastSuccessTrigger"])
        assertTrue(fields.filterKeys { it.endsWith("Condition") }.values.all { it == true })
    }
}
