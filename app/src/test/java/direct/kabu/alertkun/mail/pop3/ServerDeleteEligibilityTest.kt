package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.model.*
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ServerDeleteEligibilityTest {
    private val first = 1_700_000_000_000L
    private fun mail(at: Long = first) = ReceivedMail("local", "account", "uidl", at, null,
        mapOf("Date" to "0"), "ordinary", null, false, MailProcessingState.PROCESSED, false, null)
    private fun eligible(policy: ServerDeletePolicy, now: Long, local: ReceivedMail? = mail()) =
        ServerDeleteEligibility.eligible(policy, "account", "uidl", local, now)

    @Test fun newAndLegacyAccountsDefaultToNeverAndAllPoliciesHaveStableNames() {
        val account = MailAccount("a", "A", MailProtocol.POP3, "localhost", 110, "u", "key", true, null, true)
        assertEquals(ServerDeletePolicy.NEVER, account.serverDeletePolicy)
        assertEquals(7, ServerDeletePolicy.entries.size)
        ServerDeletePolicy.entries.forEach { assertEquals(it, ServerDeletePolicy.valueOf(it.name)) }
    }

    @Test fun neverRetainsAndImmediatelyAllowsUnmatchedProcessedOnly() {
        assertFalse(eligible(ServerDeletePolicy.NEVER, Long.MAX_VALUE))
        assertTrue(eligible(ServerDeletePolicy.IMMEDIATELY, first))
        assertFalse(eligible(ServerDeletePolicy.IMMEDIATELY, first, mail().copy(processingState = MailProcessingState.RECEIVED)))
    }

    @Test fun unknownMismatchedAndInvalidTimestampsFailClosed() {
        val policy = ServerDeletePolicy.IMMEDIATELY
        assertFalse(eligible(policy, first, null))
        assertFalse(eligible(policy, first, mail().copy(accountId = "other")))
        assertFalse(eligible(policy, first, mail().copy(remoteMessageId = "other")))
        assertFalse(eligible(policy, first, mail(0)))
        assertFalse(eligible(policy, first, mail(-1)))
        assertFalse(eligible(policy, first, mail(first + 1)))
        assertFalse(ServerDeleteEligibility.eligible(policy, "", "uidl", mail(), first))
        assertFalse(eligible(ServerDeletePolicy.AFTER_15_DAYS, Long.MAX_VALUE, mail(Long.MAX_VALUE - 1)))
    }

    @Test fun elapsedDayBoundariesIncludeExactlyTheRequiredDayAndNeverBefore() {
        val policies = mapOf(ServerDeletePolicy.AFTER_1_DAY to 1, ServerDeletePolicy.AFTER_1_WEEK to 7,
            ServerDeletePolicy.AFTER_10_DAYS to 10, ServerDeletePolicy.AFTER_15_DAYS to 15)
        policies.forEach { (policy, days) ->
            val boundary = first + days * 86_400_000L
            assertFalse("$policy 23:59:59", eligible(policy, boundary - 1_000))
            assertFalse("$policy last millisecond", eligible(policy, boundary - 1))
            assertTrue("$policy exact boundary", eligible(policy, boundary))
            assertTrue("$policy after", eligible(policy, boundary + 1))
        }
    }

    @Test fun monthUsesUtcCalendarAndClampsMonthEndIncludingLeapYears() {
        for ((start, end) in listOf(Triple(2026, 9, 5) to Triple(2026, 10, 5),
            Triple(2026, 0, 31) to Triple(2026, 1, 28), Triple(2024, 0, 31) to Triple(2024, 1, 29))) {
            fun millis(date: Triple<Int, Int, Int>) = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(date.first, date.second, date.third, 12, 30, 0)
            }.timeInMillis
            val local = mail(millis(start))
            assertFalse(eligible(ServerDeletePolicy.AFTER_1_MONTH, millis(end) - 1, local))
            assertTrue(eligible(ServerDeletePolicy.AFTER_1_MONTH, millis(end), local))
        }
    }
}
