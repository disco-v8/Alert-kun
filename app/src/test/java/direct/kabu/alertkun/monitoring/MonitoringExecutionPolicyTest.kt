package direct.kabu.alertkun.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MonitoringExecutionPolicyTest {
    @Test fun staleOrDisabledWorkerCannotPublishBackupState() {
        assertFalse(MonitoringExecutionPolicy.mayReschedule(true, 14, 13))
        assertFalse(MonitoringExecutionPolicy.mayReschedule(false, 14, 14))
        assertTrue(MonitoringExecutionPolicy.mayReschedule(true, 14, 14))
    }

    @Test fun nextDelayUsesCheckStartAndDoesNotAccumulateWorkDuration() {
        val started = 12 * 60_000L
        val target = MonitoringExecutionPolicy.calculateNextTarget(started, 5)
        assertEquals(17 * 60_000L, target)
        assertEquals(4 * 60_000L + 50_000L, MonitoringExecutionPolicy.delayMillis(target, started + 10_000L))
    }

    @Test fun delayIsZeroWhenTheStartBasedTargetHasAlreadyPassed() {
        assertEquals(0L, MonitoringExecutionPolicy.delayMillis(100L, 101L))
    }

    @Test fun offlineIsASeparateFailureKind() {
        assertEquals(MonitoringFailureKind.DEVICE_OFFLINE, MonitoringFailureKind.valueOf("DEVICE_OFFLINE"))
        assertEquals(MonitoringFailureKind.SERVER_UNAVAILABLE, MonitoringFailureKind.valueOf("SERVER_UNAVAILABLE"))
    }

    @Test fun fiveMinuteTargetRoundsToTheExpectedMinuteBoundary() {
        assertTarget(0, 0, 0, 34, 0)
        assertTarget(12, 0, 0, 34, 0)
        assertTarget(30, 0, 0, 34, 0)
        assertTarget(31, 0, 0, 35, 0)
        assertTarget(58, 0, 0, 35, 0)
    }

    @Test fun targetRoundingHandlesHourAndDayBoundaries() {
        assertEquals(time(20, 5, 0), MonitoringExecutionPolicy.calculateNextTarget(time(19, 59, 58), 5, TOKYO))
        assertEquals(time(0, 5, 0, day = 2), MonitoringExecutionPolicy.calculateNextTarget(time(23, 59, 58), 5, TOKYO))
    }

    @Test fun thirtySecondBoundaryRoundsDownButThirtySecondsAndOneMillisecondRoundsUp() {
        assertEquals(time(19, 34, 0), MonitoringExecutionPolicy.calculateNextTarget(time(19, 29, 29, 999), 5, TOKYO))
        assertEquals(time(19, 34, 0), MonitoringExecutionPolicy.calculateNextTarget(time(19, 29, 30), 5, TOKYO))
        assertEquals(time(19, 35, 0), MonitoringExecutionPolicy.calculateNextTarget(time(19, 29, 30, 1), 5, TOKYO))
    }

    @Test fun fourteenAndFifteenMinuteIntervalsUseTheSameRoundedTargetCalculation() {
        val startedAt = time(19, 29, 58)
        assertEquals(time(19, 44, 0), MonitoringExecutionPolicy.calculateNextTarget(startedAt, 14, TOKYO))
        assertEquals(time(19, 45, 0), MonitoringExecutionPolicy.calculateNextTarget(startedAt, 15, TOKYO))
    }

    private fun assertTarget(second: Int, millisecond: Int, expectedSecond: Int, expectedMinute: Int, expectedMillisecond: Int) {
        assertEquals(
            time(19, expectedMinute, expectedSecond, expectedMillisecond),
            MonitoringExecutionPolicy.calculateNextTarget(time(19, 29, second, millisecond), 5, TOKYO),
        )
    }

    private fun time(hour: Int, minute: Int, second: Int, millisecond: Int = 0, day: Int = 1): Long =
        Calendar.getInstance(TOKYO).apply {
            clear()
            set(2026, Calendar.JANUARY, day, hour, minute, second)
            set(Calendar.MILLISECOND, millisecond)
        }.timeInMillis

    private companion object {
        val TOKYO: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }
}
