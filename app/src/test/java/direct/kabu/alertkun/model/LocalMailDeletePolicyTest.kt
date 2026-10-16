package direct.kabu.alertkun.model

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class LocalMailDeletePolicyTest {
    private val first = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test fun hourSlotIsStableWithinLocalHourAndChangesAtZeroMinutes() {
        val zone = TimeZone.getTimeZone("Asia/Tokyo")
        val hour = GregorianCalendar(zone).apply { clear(); set(2026, Calendar.OCTOBER, 15, 18, 0) }.timeInMillis
        assertEquals(hour, LocalMailRetention.hourStart(hour, zone))
        assertEquals(hour, LocalMailRetention.hourStart(hour + 3_599_999, zone))
        assertEquals(hour + 3_600_000, LocalMailRetention.hourStart(hour + 3_600_000, zone))
        val halfHourZone = TimeZone.getTimeZone("Asia/Kolkata")
        assertEquals(0, GregorianCalendar(halfHourZone).apply {
            timeInMillis = LocalMailRetention.hourStart(hour, halfHourZone)
        }.get(Calendar.MINUTE))
    }

    @Test fun elapsedDayBoundariesIncludeExactDeadline() {
        listOf(ServerDeletePolicy.AFTER_1_DAY to 1, ServerDeletePolicy.AFTER_1_WEEK to 7,
            ServerDeletePolicy.AFTER_10_DAYS to 10, ServerDeletePolicy.AFTER_15_DAYS to 15).forEach { (policy, days) ->
            val deadline = first + days * day
            assertFalse(LocalMailRetention.isExpired(policy, first, deadline - 1))
            assertTrue(LocalMailRetention.isExpired(policy, first, deadline))
            assertTrue(LocalMailRetention.isExpired(policy, first, deadline + 1))
        }
    }

    @Test fun immediatelyAndNeverAreDistinct() {
        assertTrue(LocalMailRetention.isExpired(ServerDeletePolicy.IMMEDIATELY, first, first))
        assertFalse(LocalMailRetention.isExpired(ServerDeletePolicy.NEVER, first, first + 999 * day))
    }

    @Test fun invalidFutureAndOverflowTimesAreRetained() {
        ServerDeletePolicy.entries.forEach { policy ->
            assertFalse(LocalMailRetention.isExpired(policy, 0, first))
            assertFalse(LocalMailRetention.isExpired(policy, -1, first))
            assertFalse(LocalMailRetention.isExpired(policy, first + 1, first))
        }
        assertFalse(LocalMailRetention.isExpired(ServerDeletePolicy.AFTER_1_DAY, Long.MAX_VALUE - 10, Long.MAX_VALUE))
    }

    @Test fun monthUsesUtcCalendarIncludingLeapYearEnd() {
        fun at(year: Int, month: Int, date: Int) = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, date, 12, 30)
        }.timeInMillis
        val january = at(2024, Calendar.JANUARY, 31)
        val february = at(2024, Calendar.FEBRUARY, 29)
        assertFalse(LocalMailRetention.isExpired(ServerDeletePolicy.AFTER_1_MONTH, january, february - 1))
        assertTrue(LocalMailRetention.isExpired(ServerDeletePolicy.AFTER_1_MONTH, january, february))
    }
}
