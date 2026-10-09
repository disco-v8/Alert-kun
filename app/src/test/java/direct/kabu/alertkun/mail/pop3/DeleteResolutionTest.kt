package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.data.database.Converters
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeleteResolutionTest {
    private val now = 1_700_000_000_000L
    private fun mail(uidl: String) = ReceivedMail(uidl, "deletion-test", uidl, now, null, emptyMap(),
        null, null, false, MailProcessingState.PROCESSED, false, null)

    @Test fun resolvedMailsAreNotEligibleAndUnknownStateRemainsPending() {
        for (state in ServerDeleteResolution.entries) {
            assertEquals(state == ServerDeleteResolution.PENDING, ServerDeleteEligibility.eligible(
                ServerDeletePolicy.IMMEDIATELY, "deletion-test", "one", mail("one").copy(serverDeleteResolution = state), now))
        }
        assertEquals(ServerDeleteResolution.PENDING, Converters().toServerDeleteResolution("unknown"))
    }

    @Test fun successfulListingReturnsDeletedAndAbsentSeparatelyWithoutLoggingUidl() = runBlocking {
        Pop3TestServer(listOf("private-present")).use { server ->
            val events = mutableListOf<Map<String, Any?>>()
            val result = Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret",
                observation = { _, fields -> events += fields }).deleteProcessed({ _, uidl -> mail(uidl) }, now,
                setOf("private-present", "private-absent"))
            assertEquals(setOf("private-present"), result.first)
            assertEquals(setOf("private-absent"), result.second)
            assertTrue(events.any { it["matched"] == 1 && it["missing"] == 1 })
            assertFalse(events.toString().contains("private-"))
            assertFalse(events.toString().contains("fixture-secret"))
        }
    }
}
