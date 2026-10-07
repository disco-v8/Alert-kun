package direct.kabu.alertkun.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockChangePolicyTest {
    private val baseline = ClockBaseline(wallClockMillis = 1_000_000L, elapsedRealtimeMillis = 10_000L)

    @Test fun adjustmentUsesWallClockAgainstElapsedRealtime() {
        assertEquals(10_000L, ClockChangePolicy.adjustmentMillis(baseline, 1_020_000L, 20_000L))
    }

    @Test fun smallAdjustmentsDoNotRebase() {
        assertFalse(ClockChangePolicy.requiresRebase(10_000L))
        assertFalse(ClockChangePolicy.requiresRebase(-30_000L))
        assertFalse(ClockChangePolicy.requiresRebase(59_999L))
    }

    @Test fun thresholdAndLargeAdjustmentsRebase() {
        assertTrue(ClockChangePolicy.requiresRebase(60_000L))
        assertTrue(ClockChangePolicy.requiresRebase(-60_000L))
        assertTrue(ClockChangePolicy.requiresRebase(5 * 60_000L))
        assertTrue(ClockChangePolicy.requiresRebase(-60 * 60_000L))
    }

    @Test fun trackerRefreshesItsBaselineAfterEachTimeChange() {
        val tracker = ClockBaselineTracker()
        tracker.initialize(1_000_000L, 10_000L)
        assertTrue(tracker.recordTimeChanged(1_070_000L, 20_000L))
        assertFalse(tracker.recordTimeChanged(1_080_000L, 30_000L))
    }

    @Test fun timezoneChangeOnlyRequestsADisplayRefresh() {
        val before = MonitoringTimeChangeStore.revision.value
        MonitoringTimeChangeStore.notifyTimezoneChanged()
        assertEquals(before + 1L, MonitoringTimeChangeStore.revision.value)
    }
}
