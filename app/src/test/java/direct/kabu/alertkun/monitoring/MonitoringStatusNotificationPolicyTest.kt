package direct.kabu.alertkun.monitoring

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class MonitoringStatusNotificationPolicyTest {
    @Test fun lowAndMinRemainSilentDespiteRetainedSoundSettings() {
        assertTrue(MonitoringStatusNotificationPolicy.isSilent(2, true, true))
        assertTrue(MonitoringStatusNotificationPolicy.isSilent(1, true, true))
    }

    @Test fun defaultRequiresBothSoundAndVibrationDisabled() {
        assertTrue(MonitoringStatusNotificationPolicy.isSilent(3, false, false))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(3, true, false))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(3, false, true))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(3, null, false))
    }

    @Test fun highImportanceWithoutSoundIsNotProvenSilent() {
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(4, false, false))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(5, false, false))
    }

    @Test fun unknownBlockedAndPreChannelStatesAreNotProvenSilent() {
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(null, null, null))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(-1000, false, false))
        assertFalse(MonitoringStatusNotificationPolicy.isSilent(0, false, false))
    }

    @Test fun japaneseAndEnglishDatesUseLocalMinutePrecision() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val time = GregorianCalendar(tokyo).apply {
            clear(); set(2026, Calendar.OCTOBER, 9, 22, 35, 59)
        }.timeInMillis
        assertEquals("2026年10月09日 22:35", MonitoringStatusNotificationPolicy.timestamp(time, "yyyy年MM月dd日 HH:mm", Locale.JAPANESE, tokyo))
        assertEquals("Oct 9, 2026 22:35", MonitoringStatusNotificationPolicy.timestamp(time, "MMM d, yyyy HH:mm", Locale.ENGLISH, tokyo))
    }

    @Test fun dateFollowsTimezoneRatherThanBeingFixedToJst() {
        val time = GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            clear(); set(2026, Calendar.OCTOBER, 9, 22, 35, 0)
        }.timeInMillis
        assertEquals("Oct 9, 2026 13:35", MonitoringStatusNotificationPolicy.timestamp(time, "MMM d, yyyy HH:mm", Locale.ENGLISH, TimeZone.getTimeZone("UTC")))
    }
}
