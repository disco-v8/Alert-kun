package direct.kabu.alertkun.mail.pop3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Dedicated fixture account + real TCP POP3 + real Room, no access to the user's account DB. */
class ServerDeletionInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val credentials = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }
    private fun check(database: AlertKunDatabase, server: Pop3TestServer, policy: ServerDeletePolicy,
        now: () -> Long, onAlarm: () -> Unit = {}): MailCheckCoordinator {
        val repository = MailRepository(database.receivedMailDao())
        return MailCheckCoordinator(object : MonitoringMailStore {
            override suspend fun finalizeBatch(outcomes: List<MailProcessingOutcome>) = repository.finalizeBatch(outcomes)
            override suspend fun receivedMails() = repository.receivedMails()
            override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = repository.getByRemoteId(accountId, remoteMessageId)
            override suspend fun saveReceived(mail: ReceivedMail): Boolean {
                if (repository.exists(mail.accountId, mail.remoteMessageId)) return false
                repository.saveReceived(mail); return true
            }
            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) =
                repository.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
        }, object : MonitoringRuleSource {
            override suspend fun allRules() = listOf(AlertRule("r", "Fixture rule", "ALERT", RuleTarget.SUBJECT,
                1, null, VibrationPattern.NONE, AlarmMode.ONCE, true))
        }, AccountMailReceiver(listOf(server.account(policy)), credentials), object : MonitoringAlarmSink {
            override fun start(event: AlarmEvent) = onAlarm()
        }, now = now)
    }

    @Test fun neverAndImmediatelyUseRealTcpAndDeleteOnlyAfterRoomProcessed() = runBlocking {
        for (policy in listOf(ServerDeletePolicy.NEVER, ServerDeletePolicy.IMMEDIATELY)) {
            Pop3TestServer().use { server ->
                Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build().useDatabase { database ->
                    var alarms = 0
                    val coordinator = check(database, server, policy, { 1_700_000_000_000L }) {
                        assertFalse(server.commands.any { it.startsWith("DELE") }); alarms++
                    }
                    assertTrue(coordinator.check().success)
                    assertTrue(coordinator.check().success)
                    val local = database.receivedMailDao().getByRemoteMessageId("deletion-test", "test-uid-1")!!
                    assertEquals(MailProcessingState.PROCESSED, local.processingState)
                    assertEquals(1, alarms)
                    assertEquals(policy == ServerDeletePolicy.IMMEDIATELY, server.commands.any { it.startsWith("DELE") })
                    assertEquals(if (policy == ServerDeletePolicy.NEVER) 1 else 0, server.remaining().size)
                }
            }
        }
    }

    @Test fun delayedClockUsesFirstSaveInsteadOfHeaderDateAndDoesNotRealert() = runBlocking {
        Pop3TestServer().use { server ->
            Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build().useDatabase { database ->
                val first = 1_700_000_000_000L; var clock = first; var alarms = 0
                val coordinator = check(database, server, ServerDeletePolicy.AFTER_1_DAY, { clock }) { alarms++ }
                assertTrue(coordinator.check().success)
                clock = first + 86_400_000L - 1
                assertTrue(coordinator.check().success)
                assertFalse(server.commands.any { it.startsWith("DELE") })
                assertEquals(first, database.receivedMailDao().getByRemoteMessageId("deletion-test", "test-uid-1")!!.receivedAtEpochMillis)
                clock++
                assertTrue(coordinator.check().success)
                assertTrue(server.remaining().isEmpty()); assertEquals(1, alarms)
            }
        }
    }

    @Test fun deleteFailureRetainsProcessedAndRetriesWithoutDuplicateAlarm() = runBlocking {
        Pop3TestServer(listOf("one", "two")).use { server ->
            Room.inMemoryDatabaseBuilder(context, AlertKunDatabase::class.java).build().useDatabase { database ->
                var alarms = 0
                server.rejectDeleteNumber = 2
                val coordinator = check(database, server, ServerDeletePolicy.IMMEDIATELY, { 1_700_000_000_000L }) { alarms++ }
                assertFalse(coordinator.check().success)
                assertEquals(2, server.remaining().size)
                assertTrue(database.receivedMailDao().getByProcessingState(MailProcessingState.RECEIVED).isEmpty())
                server.rejectDeleteNumber = null
                assertTrue(coordinator.check().success)
                assertTrue(server.remaining().isEmpty()); assertEquals(1, alarms)
            }
        }
    }

    @Test fun connectionTestWithImmediatePolicyNeverSendsDele() = runBlocking {
        Pop3TestServer().use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY)
            AccountMailReceiver(listOf(account), credentials).testConnection(account)
            assertFalse(server.commands.any { it.startsWith("DELE") })
            assertEquals(1, server.remaining().size)
        }
    }
}

private inline fun <T> AlertKunDatabase.useDatabase(block: (AlertKunDatabase) -> T): T = try { block(this) } finally { close() }
