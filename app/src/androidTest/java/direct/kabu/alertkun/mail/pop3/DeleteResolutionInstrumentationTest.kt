package direct.kabu.alertkun.mail.pop3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.entity.toEntity
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Disposable mailbox and isolated Room; never deletes a production mail. */
class DeleteResolutionInstrumentationTest {
    private val now = 1_700_000_000_000L
    private val credentials = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }

    private suspend fun fixture(test: suspend (Pop3TestServer, MailRepository, AccountMailReceiver) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build()
        try {
            Pop3TestServer(listOf("present")).use { server ->
                val repository = MailRepository(db.receivedMailDao())
                for (uidl in listOf("present", "absent")) {
                    db.receivedMailDao().insertReceivedMail(ReceivedMail(uidl, "deletion-test", uidl, now,
                        null, emptyMap(), null, null, false, MailProcessingState.PROCESSED, false, null).toEntity())
                }
                val receiver = AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)), credentials,
                    localProcessedMails = repository::deleteCandidatesForAccount, resolveDeletes = repository::resolveDeletes)
                test(server, repository, receiver)
            }
        } finally { db.close() }
    }

    @Test fun confirmedDeletedAndAbsentPersistAndNextCheckNeverConnects() = runBlocking {
        fixture { server, repository, receiver ->
            receiver.deleteProcessedMessages(repository::getByRemoteId, now)
            assertEquals(ServerDeleteResolution.DELETED, repository.getByRemoteId("deletion-test", "present")!!.serverDeleteResolution)
            assertEquals(ServerDeleteResolution.ABSENT, repository.getByRemoteId("deletion-test", "absent")!!.serverDeleteResolution)
            assertTrue(repository.deleteCandidatesForAccount("deletion-test").isEmpty())
            val before = server.commands.toList()
            receiver.deleteProcessedMessages(repository::getByRemoteId, now)
            assertEquals(before, server.commands.toList())
            assertEquals(MailProcessingState.PROCESSED, repository.getByRemoteId("deletion-test", "present")!!.processingState)
        }
    }

    @Test fun incompleteUidlKeepsAllCandidates() = runBlocking { failureKeepsCandidates({ it.incompleteUidl = true }) }
    @Test fun rejectedDeleKeepsAllCandidatesAndDoesNotQuit() = runBlocking {
        failureKeepsCandidates({ it.rejectDeleteNumber = 1 }, forbidQuit = true)
    }
    @Test fun unknownQuitKeepsAllCandidatesThenAbsenceCanResolveThem() = runBlocking {
        fixture { server, repository, receiver ->
            server.loseQuitResponse = true
            try { receiver.deleteProcessedMessages(repository::getByRemoteId, now); fail("Expected unknown QUIT") }
            catch (_: MailReceiverException) { }
            assertEquals(2, repository.deleteCandidatesForAccount("deletion-test").size)
            server.loseQuitResponse = false
            receiver.deleteProcessedMessages(repository::getByRemoteId, now)
            assertTrue(repository.deleteCandidatesForAccount("deletion-test").isEmpty())
            assertEquals(ServerDeleteResolution.ABSENT, repository.getByRemoteId("deletion-test", "present")!!.serverDeleteResolution)
        }
    }

    private suspend fun failureKeepsCandidates(configure: (Pop3TestServer) -> Unit, forbidQuit: Boolean = false) {
        fixture { server, repository, receiver ->
            configure(server)
            try { receiver.deleteProcessedMessages(repository::getByRemoteId, now); fail("Expected failure") }
            catch (_: MailReceiverException) { }
            assertEquals(2, repository.deleteCandidatesForAccount("deletion-test").size)
            assertTrue(repository.deleteCandidatesForAccount("deletion-test").all { it.serverDeleteResolution == ServerDeleteResolution.PENDING })
            if (forbidQuit) assertFalse(server.commands.contains("QUIT"))
        }
    }
}
