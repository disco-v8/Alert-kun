package direct.kabu.alertkun.ui.main

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringTimestampFormatterTest {
    @Test fun registrationProvidesEstimateBeforeTheFirstFcmReceipt() {
        val registeredAt = 13L * 3_600_000 + 40 * 60_000
        assertEquals("1970/01/01 13:43", formatMonitoringTimestamp(
            nextFcmCheckAt(null, 3, registeredAt)!!, Locale.US, TimeZone.getTimeZone("UTC")))
        assertEquals(registeredAt + 300_000L, nextFcmCheckAt(null, 5, registeredAt))
        assertEquals(null, nextFcmCheckAt(null, 3, null))
        assertEquals(null, nextFcmCheckAt(null, 3, 0))
    }

    @Test fun fcmReceiptTakesPrecedenceEvenOverMoreRecentRegistration() {
        assertEquals(240_000L, nextFcmCheckAt(60_000L, 3, 120_000L))
    }
    @Test fun nextCheckUsesFcmReceiptAndCurrentUserInterval() {
        val receivedAt = 13L * 3_600_000 + 40 * 60_000
        assertEquals("1970/01/01 13:43", formatMonitoringTimestamp(
            nextFcmCheckAt(receivedAt, 3)!!, Locale.US, TimeZone.getTimeZone("UTC")))
        assertEquals(receivedAt + 5 * 60_000, nextFcmCheckAt(receivedAt, 5))
        assertEquals(receivedAt + 6 * 60_000, nextFcmCheckAt(receivedAt + 3 * 60_000, 3))
    }

    @Test fun missingReceiptAndInvalidOrOverflowingTimesHaveNoEstimate() {
        assertEquals(null, nextFcmCheckAt(null, 3))
        assertEquals(null, nextFcmCheckAt(0, 3))
        assertEquals(null, nextFcmCheckAt(1, 0))
        assertEquals(null, nextFcmCheckAt(Long.MAX_VALUE, 3))
    }
    @Test fun formatsEpochMillisWithTheSuppliedLocalTimeZone() {
        assertEquals(
            "1970/01/01 09:00",
            formatMonitoringTimestamp(0L, Locale.US, TimeZone.getTimeZone("Asia/Tokyo")),
        )
    }

    @Test fun formatsDateAndTimeWithoutExposingTheRawEpochMillis() {
        assertEquals(
            "1970/01/01 00:01",
            formatMonitoringTimestamp(60_000L, Locale.US, TimeZone.getTimeZone("UTC")),
        )
    }
}
