package direct.kabu.alertkun.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MonitoringSchedulerTest {
    @Test fun allIntervalsUsePeriodicBackup() {
        assertEquals(MonitoringScheduleKind.PERIODIC, MonitoringScheduler.kindFor(1))
        assertEquals(MonitoringScheduleKind.PERIODIC, MonitoringScheduler.kindFor(14))
        assertEquals(MonitoringScheduleKind.PERIODIC, MonitoringScheduler.kindFor(15))
        assertEquals(MonitoringScheduleKind.PERIODIC, MonitoringScheduler.kindFor(1500))
    }

    @Test fun generationPreventsOldWorkersFromRescheduling() {
        assertFalse(MonitoringExecutionPolicy.mayReschedule(false, 4, 4))
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, 5, 4))
        assertTrue(MonitoringExecutionPolicy.mayReschedule(true, 4, 4))
    }

    @Test fun intervalChangesAndStopInvalidatePreviouslyScheduledShortWorker() {
        val generationForFiveMinutes = 7L
        val generationForFifteenMinutes = 8L // 5 -> 15
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, generationForFifteenMinutes, generationForFiveMinutes))

        val generationForFiveMinutesAgain = 9L // 15 -> 5
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, generationForFiveMinutesAgain, generationForFifteenMinutes))
        assertFalse(MonitoringExecutionPolicy.mayReschedule(false, 10L, generationForFiveMinutesAgain)) // ON -> OFF
    }

    @Test fun periodicInitialTargetIsNowPlusTheConfiguredInterval() {
        val now = 17 * 60 * 60_000L + 50 * 60_000L

        assertEquals(now + 15 * 60_000L, MonitoringScheduler.initialNextScheduledAt(now, 15))
        assertEquals(now + 30 * 60_000L, MonitoringScheduler.initialNextScheduledAt(now, 30))
        assertFalse(now == MonitoringScheduler.initialNextScheduledAt(now, 15))
    }

    @Test fun periodicWorkerNextTargetUsesTheActualCheckStartTime() {
        val startedAt = 18 * 60 * 60_000L + 7 * 60_000L

        assertEquals(startedAt + 15 * 60_000L, MonitoringExecutionPolicy.calculateNextTarget(startedAt, 15))
    }

    @Test fun switchingAcrossThePeriodicBoundaryUsesTheAppropriateInitialTarget() {
        val now = 1_000L

        assertEquals(15 * 60_000L, MonitoringScheduler.initialNextScheduledAt(now, 14)) // Both use a 15-minute backup
        assertEquals(15 * 60_000L, MonitoringScheduler.initialNextScheduledAt(now, 15)) // 14 -> 15: rounded to 00:15:00
    }

    @Test fun intervalChangeUsesTheChangeTimeRatherThanAnOlderCheckStart() {
        val oldCheckStartedAt = time(10, 44, 58)
        val changedAt = time(10, 45, 30, 1)

        assertEquals(time(11, 1, 0), MonitoringScheduler.initialNextScheduledAt(changedAt, 5))
        assertFalse(MonitoringScheduler.initialNextScheduledAt(changedAt, 5) == MonitoringExecutionPolicy.calculateNextTarget(oldCheckStartedAt, 5))
    }

    @Test fun shortUserIntervalRoundsFifteenMinuteBackupFromChangeTime() {
        assertEquals(time(11, 0, 0), MonitoringScheduler.initialNextScheduledAt(time(10, 45, 12), 5))
        assertEquals(time(11, 0, 0), MonitoringScheduler.initialNextScheduledAt(time(10, 45, 30), 5))
        assertEquals(time(11, 1, 0), MonitoringScheduler.initialNextScheduledAt(time(10, 45, 30, 1), 5))
        assertEquals(time(11, 1, 0), MonitoringScheduler.initialNextScheduledAt(time(10, 45, 58), 5))
    }

    @Test fun allScheduleKindsRebaseFromTheIntervalChangeTime() {
        val changedAt = time(10, 45, 58)

        assertEquals(time(11, 1, 0), MonitoringScheduler.initialNextScheduledAt(changedAt, 14))
        assertEquals(time(11, 1, 0), MonitoringScheduler.initialNextScheduledAt(changedAt, 15))
        assertEquals(time(11, 16, 0), MonitoringScheduler.initialNextScheduledAt(changedAt, 30))
    }

    private fun time(hour: Int, minute: Int, second: Int, millisecond: Int = 0): Long =
        Calendar.getInstance(TOKYO).apply {
            clear()
            set(2026, Calendar.JANUARY, 1, hour, minute, second)
            set(Calendar.MILLISECOND, millisecond)
        }.timeInMillis

    private companion object {
        val TOKYO: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }
}
