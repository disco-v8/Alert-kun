package direct.kabu.alertkun.monitoring

import org.junit.Assert.*
import org.junit.Test

class MonitoringHealthNotificationPolicyTest {
    private val day = MonitoringHealthNotificationPolicy.DAY_MILLIS
    private val now = 10 * day
    private fun healthy() = MonitoringHealthStatus(true, true, now - 2 * day, now - day,
        null, now, true, 0)

    @Test fun oneSecondBeforeTwentyFourHoursIsNotDue() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now,
            healthy().copy(lastNotificationAt = now - day + 1_000)))
    }
    @Test fun exactlyTwentyFourHoursIsDue() {
        assertTrue(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy()))
    }
    @Test fun monitoringOffNeverReportsEvenAfterManualSuccess() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(monitoringEnabled = false)))
    }
    @Test fun notificationSettingOffNeverReports() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(notificationEnabled = false)))
    }
    @Test fun unresolvedFailureIsNotHealthy() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(consecutiveFailureCount = 1)))
    }
    @Test fun failureInsideWindowIsNotHealthyEvenAfterRecovery() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastFailedCheckAt = now - 1)))
    }
    @Test fun failureExactlyAtWindowStartIsNotHealthy() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastFailedCheckAt = now - day)))
    }
    @Test fun failureOlderThanWindowAllowsHealthyReport() {
        assertTrue(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastFailedCheckAt = now - day - 1)))
    }
    @Test fun firstRunAndFirstSuccessDoNotImmediatelyReport() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(observingSince = null, lastNotificationAt = null)))
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(observingSince = now, lastNotificationAt = null)))
    }
    @Test fun firstReportRequiresExactlyTwentyFourHoursOfEvidence() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(observingSince = now - day + 1, lastNotificationAt = null)))
        assertTrue(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(observingSince = now - day, lastNotificationAt = null)))
    }
    @Test fun pendingFailedAndSkippedChecksCannotBecomeNewSuccess() {
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastAttemptSucceeded = false)))
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastSuccessfulCheckAt = now - 1)))
        assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, healthy().copy(lastSuccessfulCheckAt = null)))
    }
    @Test fun backwardClockAndFutureTimestampsCannotProduceRepeatedNotifications() {
        listOf(healthy().copy(lastNotificationAt = now + 1), healthy().copy(observingSince = now + 1),
            healthy().copy(lastFailedCheckAt = now + 1), healthy().copy(lastSuccessfulCheckAt = now + 1))
            .forEach { assertFalse(MonitoringHealthNotificationPolicy.shouldNotify(now, it)) }
    }
    @Test fun zeroAlertsStillReportsHealthyMonitoring() {
        assertEquals(MonitoringHealthReport(0, now), MonitoringHealthNotificationPolicy.report(now, healthy(), 0))
    }
    @Test fun detectedAlertsAreIndependentOfMonitoringHealth() {
        assertEquals(MonitoringHealthReport(3, now), MonitoringHealthNotificationPolicy.report(now, healthy(), 3))
        assertNull(MonitoringHealthNotificationPolicy.report(now, healthy().copy(lastFailedCheckAt = now), 3))
    }
}
