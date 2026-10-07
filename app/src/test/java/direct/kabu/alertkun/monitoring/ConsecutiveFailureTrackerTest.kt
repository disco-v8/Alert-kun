package direct.kabu.alertkun.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsecutiveFailureTrackerTest {
    @Test fun failuresIncrementAndThresholdIsReportedOnceUntilSuccess() {
        val tracker = ConsecutiveFailureTracker()
        tracker.onFailure(); tracker.onFailure()
        assertEquals(2, tracker.currentCount)
        assertTrue(tracker.consumeThresholdNotification(2))
        assertFalse(tracker.consumeThresholdNotification(2))
        tracker.onSuccess()
        assertEquals(0, tracker.currentCount)
        tracker.onFailure(); tracker.onFailure()
        assertTrue(tracker.consumeThresholdNotification(2))
    }
}
