package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Pop3ServerDeletionTest {
    private val now = 1_700_000_000_000L
    private fun mail(uidl: String) = ReceivedMail(uidl, "deletion-test", uidl, now, null, emptyMap(), null,
        null, false, MailProcessingState.PROCESSED, false, null)

    @Test fun neverDoesNotConnectAndImmediateChecksEveryLookupBeforeDeleAndQuit() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            Pop3ServerDeletion(server.account(ServerDeletePolicy.NEVER), "fixture-secret").deleteProcessed({ _, _ -> error("Never lookup") }, now)
            assertTrue(server.commands.isEmpty())
            var lookups = 0
            Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret").deleteProcessed({ account, uidl ->
                assertEquals("deletion-test", account)
                assertFalse(server.commands.any { it.startsWith("DELE") })
                lookups++; mail(uidl)
            }, now)
            assertEquals(2, lookups)
            assertEquals(listOf("USER", "PASS", "UIDL", "DELE 1", "DELE 2", "QUIT"), server.commands.toList())
            assertTrue(server.remaining().isEmpty())
        }
    }

    @Test fun unknownReceivedAndWrongAccountOrUidlAreNeverDeleted() = runBlocking {
        Pop3TestServer(listOf("unknown", "received", "wrong-account", "wrong-uidl", "processed")).use { server ->
            Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret").deleteProcessed({ _, uidl ->
                when (uidl) {
                    "unknown" -> null
                    "received" -> mail(uidl).copy(processingState = MailProcessingState.RECEIVED)
                    "wrong-account" -> mail(uidl).copy(accountId = "other")
                    "wrong-uidl" -> mail(uidl).copy(remoteMessageId = "other")
                    else -> mail(uidl)
                }
            }, now)
            assertEquals(listOf("DELE 5"), server.commands.filter { it.startsWith("DELE") })
            assertEquals(listOf("unknown", "received", "wrong-account", "wrong-uidl"), server.remaining())
        }
    }

    @Test fun roomFailureBeforeAnyMarkAbortsWithoutQuit() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            try {
                Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret")
                    .deleteProcessed({ _, uidl -> if (uidl == "two") error("Room unavailable") else mail(uidl) }, now)
                fail("Expected failure")
            } catch (_: MailReceiverException) { }
            assertFalse(server.commands.any { it.startsWith("DELE") || it == "QUIT" })
            assertEquals(2, server.remaining().size)
        }
    }

    @Test fun rejectedDeleDoesNotCommitEarlierMarksAndNextPollCanRetry() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            server.rejectDeleteNumber = 2
            val deletion = Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret")
            try { deletion.deleteProcessed({ _, uidl -> mail(uidl) }, now); fail("Expected failure") }
            catch (_: MailReceiverException) { }
            assertFalse(server.commands.contains("QUIT"))
            assertEquals(listOf("one", "two"), server.remaining())
            server.rejectDeleteNumber = null
            deletion.deleteProcessed({ _, uidl -> mail(uidl) }, now)
            assertTrue(server.remaining().isEmpty())
        }
    }

    @Test fun interruptedSessionDoesNotCommitEarlierMarks() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            server.disconnectOnDeleteNumber = 2
            try {
                Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret").deleteProcessed({ _, uidl -> mail(uidl) }, now)
                fail("Expected failure")
            } catch (_: MailReceiverException) { }
            assertFalse(server.commands.contains("QUIT"))
            assertEquals(listOf("one", "two"), server.remaining())
        }
    }

    @Test fun ambiguousUidlListingFailsBeforeAnyLookupOrDelete() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            server.duplicateUidl = true
            try {
                Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret").deleteProcessed({ _, _ -> error("Must not lookup") }, now)
                fail("Expected failure")
            } catch (_: MailReceiverException) { }
            assertFalse(server.commands.any { it.startsWith("DELE") || it == "QUIT" })
        }
    }

    @Test fun cancellationDoesNotSendQuit() = runBlocking {
        Pop3TestServer().use { server ->
            try {
                Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY), "fixture-secret")
                    .deleteProcessed({ _, _ -> throw CancellationException("Test cancelled") }, now)
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertFalse(server.commands.any { it.startsWith("DELE") || it == "QUIT" })
        }
    }

    @Test fun imapIsUnsupportedAndNeverConnectsForDeletion() = runBlocking {
        Pop3TestServer().use { server ->
            Pop3ServerDeletion(server.account(ServerDeletePolicy.IMMEDIATELY).copy(protocol = MailProtocol.IMAPS), "fixture-secret")
                .deleteProcessed({ _, _ -> error("Unsupported protocol") }, now)
            assertTrue(server.commands.isEmpty())
        }
    }
}
