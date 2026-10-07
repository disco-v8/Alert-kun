package direct.kabu.alertkun.ui.main

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringTimestampFormatterTest {
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
