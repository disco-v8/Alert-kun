package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.security.CredentialStore
import direct.kabu.alertkun.monitoring.ObservationFormat
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalDeleteCandidatesTest {
    private val now = 1_700_000_000_000L
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var credentialReads = 0
    private val credentials = object : CredentialStore {
        override fun load(key: String): String { credentialReads++; return "fixture-secret" }
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }
    private fun mail(uidl: String = "private-uidl") = ReceivedMail(uidl, "deletion-test", uidl, now,
        null, emptyMap(), "private-subject", "private-body", true, MailProcessingState.PROCESSED, false, null)
    private fun receiver(server: Pop3TestServer, policy: ServerDeletePolicy,
        source: suspend (String) -> List<ReceivedMail>) = AccountMailReceiver(listOf(server.account(policy)), credentials,
        observation = { event, fields -> events += event to fields }, localProcessedMails = source, resolveDeletes = { _, deleted, absent -> deleted.size to absent.size })

    @Test fun emptyCandidatesNeverLoadCredentialsConnectOrLookupAndOnlyLogSafeFields() = runBlocking {
        Pop3TestServer().use { server ->
            receiver(server, ServerDeletePolicy.IMMEDIATELY) { emptyList() }
                .deleteProcessedMessages({ _, _ -> error("Remote lookup must not run") }, now)
            assertEquals(0, credentialReads)
            assertTrue(server.commands.isEmpty())
            assertEquals(listOf("DELETE_CANDIDATES", "POP3_DELETE_SKIP"), events.map { it.first })
            assertEquals(0, events.first().second["count"])
            assertEquals("NO_DELETE_CANDIDATES", events.last().second["reason"])
            assertEquals(setOf("accountId", "protocol", "operation", "count", "reason"), events.last().second.keys)
            val line = ObservationFormat.line(events.last().first, events.last().second +
                mapOf("uidl" to "private-uidl", "subject" to "private-subject", "password" to "fixture-secret"),
                now, TimeZone.getTimeZone("UTC"))
            assertTrue(line.contains("count=0"))
            assertTrue(line.contains("reason=NO_DELETE_CANDIDATES"))
            assertFalse(line.contains("private-"))
            assertFalse(line.contains("fixture-secret"))
        }
    }

    @Test fun receivedWrongAccountBlankUnknownFutureAndNotYetDueAreNotCandidates() = runBlocking {
        Pop3TestServer().use { server ->
            val invalid = listOf(mail().copy(processingState = MailProcessingState.RECEIVED),
                mail().copy(accountId = "other"), mail().copy(remoteMessageId = ""),
                mail().copy(receivedAtEpochMillis = 0), mail().copy(receivedAtEpochMillis = now + 1), mail())
            receiver(server, ServerDeletePolicy.AFTER_1_DAY) { invalid }
                .deleteProcessedMessages({ _, _ -> error("No lookup") }, now)
            assertEquals(0, credentialReads)
            assertTrue(server.commands.isEmpty())
            assertEquals(0, events.first().second["count"])
            assertFalse(events.toString().contains("private-uidl"))
            assertFalse(events.toString().contains("private-subject"))
            assertFalse(events.toString().contains("private-body"))
            assertFalse(events.toString().contains("fixture-secret"))
        }
    }

    @Test fun candidateEnumerationFailureIsNotTreatedAsAnEmptySuccess() = runBlocking {
        Pop3TestServer().use { server ->
            try {
                receiver(server, ServerDeletePolicy.IMMEDIATELY) { error("Local query failed") }
                    .deleteProcessedMessages({ _, _ -> null }, now)
                fail("Query failure must propagate")
            } catch (_: IllegalStateException) { }
            assertEquals(0, credentialReads)
            assertTrue(server.commands.isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @Test fun candidatesRequireServerPresenceAndFreshLocalEligibility() = runBlocking {
        Pop3TestServer(listOf("present", "unknown")).use { server ->
            receiver(server, ServerDeletePolicy.IMMEDIATELY) { listOf(mail("absent"), mail("present")) }
                .deleteProcessedMessages({ _, uidl -> if (uidl == "present") mail(uidl).copy(processingState = MailProcessingState.RECEIVED) else null }, now)
            assertEquals(2, events.first().second["count"])
            assertEquals(1, credentialReads)
            assertEquals(listOf("USER", "PASS", "UIDL", "QUIT"), server.commands.toList())
            assertEquals(listOf("present", "unknown"), server.remaining())
        }
    }

    @Test fun candidateDeletesOnlyEligibleServerUidlAndCommitsNormally() = runBlocking {
        Pop3TestServer(listOf("present", "unknown")).use { server ->
            receiver(server, ServerDeletePolicy.IMMEDIATELY) { listOf(mail("present"), mail("present")) }
                .deleteProcessedMessages({ _, uidl -> if (uidl == "present") mail(uidl) else null }, now)
            assertEquals(1, events.first().second["count"])
            assertEquals(listOf("USER", "PASS", "UIDL", "DELE 1", "QUIT"), server.commands.toList())
            assertEquals(listOf("unknown"), server.remaining())
        }
    }

    @Test fun neverDoesNotEnumerateCandidatesOrConnect() = runBlocking {
        Pop3TestServer().use { server ->
            receiver(server, ServerDeletePolicy.NEVER) { error("NEVER must not query") }
                .deleteProcessedMessages({ _, _ -> error("No lookup") }, now)
            assertTrue(server.commands.isEmpty())
            assertTrue(events.isEmpty())
        }
    }
}
