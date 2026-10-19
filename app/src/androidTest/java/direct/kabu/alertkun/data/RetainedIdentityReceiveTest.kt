package direct.kabu.alertkun.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.entity.ReceivedMailEntity
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.mail.pop3.Pop3MailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RetainedIdentityReceiveTest {
    @Test fun unsupportedTextStateSurvivesReopenRetentionAndDeleteResolutionWithoutSchemaChange() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="unsupported-${UUID.randomUUID()}.db"
        fun open()=Room.databaseBuilder(context,AlertKunDatabase::class.java,name).build()
        try {
            open().let { db -> try {
                assertEquals(7,db.openHelper.writableDatabase.version)
                for(account in listOf("a","b")) db.receivedMailDao().insertReceivedMail(ReceivedMailEntity(
                    account,account,"same-id",1700000000000L,null,"{}",null,null,false,
                    MailProcessingState.UNSUPPORTED,false,null))
            } finally { db.close() } }
            open().let { db -> try {
                val repository=MailRepository(db.receivedMailDao())
                assertEquals(setOf("same-id"),repository.knownRemoteIds("a"))
                assertTrue(repository.receivedMails().isEmpty())
                assertEquals(MailProcessingState.UNSUPPORTED,repository.deleteCandidatesForAccount("a").single().processingState)
                assertEquals(2,db.receivedMailDao().purgeLocalMail(ServerDeletePolicy.IMMEDIATELY,1700000001000L))
                assertTrue(repository.mails.first().isEmpty())
                assertEquals(setOf("same-id"),repository.knownRemoteIds("a"))
                assertEquals(1 to 0,repository.resolveDeletes("a",setOf("same-id"),emptySet()))
                assertEquals(ServerDeleteResolution.PENDING,repository.getByRemoteId("b","same-id")?.serverDeleteResolution)
                assertEquals(0,db.receivedMailDao().countTriggeredAlerts(0,Long.MAX_VALUE))
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun everyRetentionPolicyPreservesIdentityAcrossReopenAndSkipsBodyRules() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (days in listOf(-1, 0, 1, 7, 10, 15, 30)) {
            val name = "identity-retention-${UUID.randomUUID()}.db"
            val first = 1_700_000_000_000L
            fun open() = Room.databaseBuilder(context, AlertKunDatabase::class.java, name).build()
            try {
                open().let { db ->
                    try {
                        db.receivedMailDao().insertReceivedMail(ReceivedMailEntity("m", "a", "uid", first,
                            null, "{}", "fixture", null, false, MailProcessingState.PROCESSED, false, null))
                        assertEquals(if (days == 0) 0 else 1, db.receivedMailDao().purgeLocalMail(
                            LocalMailRetention.policy(days), first + 40 * 86_400_000L))
                    } finally { db.close() }
                }
                open().let { db ->
                    try {
                        val repository = MailRepository(db.receivedMailDao())
                        assertTrue(repository.exists("a", "uid"))
                        assertEquals(days == 0, repository.mails.first().isNotEmpty())
                        var fetches = 0
                        var saves = 0
                        val store = object : MonitoringMailStore {
                            override suspend fun knownRemoteIds(accountId: String) = repository.knownRemoteIds(accountId)
                            override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = repository.getByRemoteId(accountId, remoteMessageId)
                            override suspend fun receivedMails() = repository.receivedMails()
                            override suspend fun saveReceived(mail: ReceivedMail): Boolean { saves++; error("Known save") }
                            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) = error("Known finalize")
                        }
                        Pop3TestServer(listOf("uid")).use { server ->
                            val pop3 = Pop3MailReceiver(server.account(ServerDeletePolicy.NEVER).copy(id = "a"), "fixture-secret")
                            val receiver = object : MailReceiver by pop3 {
                                override suspend fun fetchBody(remoteMail: RemoteMail): String? { fetches++; error("Known body") }
                            }
                            val result = MailCheckCoordinator(store, object : MonitoringRuleSource {
                                override suspend fun allRules() = listOf(AlertRule("r", "Fixture", "ALERT", RuleTarget.BODY,
                                    1, null, VibrationPattern.NONE, AlarmMode.ONCE, true))
                            }, receiver, object : MonitoringAlarmSink { override fun start(event: AlarmEvent) = error("Known alarm") }).check()
                            assertTrue(result.success)
                            assertEquals(0, fetches); assertEquals(0, saves); assertEquals(0, result.checkedCount)
                            assertEquals(1, server.commands.count { it == "USER" })
                            assertTrue(server.commands.contains("UIDL"))
                            assertFalse(server.commands.any { it.startsWith("TOP ") || it.startsWith("RETR ") })
                        }
                        try {
                            db.receivedMailDao().insertReceivedMail(checkNotNull(db.receivedMailDao().getById("m")).copy(id = "duplicate"))
                            fail("UNIQUE defense must remain")
                        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
                    } finally { db.close() }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
