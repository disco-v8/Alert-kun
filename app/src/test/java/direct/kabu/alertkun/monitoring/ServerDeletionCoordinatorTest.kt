package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.common.*
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerDeletionCoordinatorTest {
    private val now = 1_700_000_000_000L
    private val rule = AlertRule("r", "Rule", "ALERT", RuleTarget.SUBJECT, 1, null, VibrationPattern.NONE, AlarmMode.ONCE, true)
    private class Store : MonitoringMailStore {
        val mails = mutableListOf<ReceivedMail>()
        var rejectSave = false
        var rejectFinalize = false
        override suspend fun receivedMails() = mails.filter { it.processingState == MailProcessingState.RECEIVED }
        override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = mails.firstOrNull { it.accountId == accountId && it.remoteMessageId == remoteMessageId }
        override suspend fun saveReceived(mail: ReceivedMail): Boolean {
            if (rejectSave) error("Save failed")
            if (findByRemoteId(mail.accountId, mail.remoteMessageId) != null) return false
            mails += mail
            return true
        }
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
            if (rejectFinalize) error("Finalize failed")
            val index = mails.indexOfFirst { it.id == id }
            mails[index] = mails[index].copy(processingState = MailProcessingState.PROCESSED, alertTriggered = alertTriggered,
                matchedRuleId = matchedRuleId, backgroundColor = backgroundColor)
        }
    }
    private val credentials = object : CredentialStore {
        override fun load(key: String) = "fixture-secret"
        override fun save(key: String, password: String) = Unit
        override fun delete(key: String) = Unit
    }
    private fun coordinator(store: Store, receiver: MailReceiver, rules: List<AlertRule> = listOf(rule), sink: (AlarmEvent) -> Unit = {}) =
        MailCheckCoordinator(store, object : MonitoringRuleSource { override suspend fun allRules() = rules }, receiver,
            object : MonitoringAlarmSink { override fun start(event: AlarmEvent) = sink(event) }, now = { now })

    @Test fun actualTcpReceiveProcessesThenDeletesAndRepeatDoesNotAlarmOrInsertAgain() = runBlocking {
        Pop3TestServer().use { server ->
            val store = Store()
            var alarms = 0
            val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)), credentials, resolveDeletes = { _, deleted, absent -> deleted.size to absent.size }, localProcessedMails = { accountId -> store.mails.filter { it.accountId == accountId } })) {
                assertEquals(MailProcessingState.RECEIVED, store.mails.single().processingState)
                assertFalse(server.commands.any { it.startsWith("DELE") }); alarms++
            }
            assertTrue(check.check().success)
            assertEquals(1, alarms)
            assertEquals(MailProcessingState.PROCESSED, store.mails.single().processingState)
            assertEquals(now, store.mails.single().receivedAtEpochMillis)
            assertTrue(server.remaining().isEmpty())
            assertTrue(check.check().success)
            assertEquals(1, alarms); assertEquals(1, store.mails.size)
        }
    }

    @Test fun deletionFailureLeavesProcessedAndRetryNeverRealertsOrDuplicates() = runBlocking {
        Pop3TestServer().use { server ->
            val store = Store(); var alarms = 0
            server.rejectDeleteNumber = 1
            val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)), credentials, resolveDeletes = { _, deleted, absent -> deleted.size to absent.size }, localProcessedMails = { accountId -> store.mails.filter { it.accountId == accountId } })) { alarms++ }
            val failed = check.check()
            assertFalse(failed.success); assertEquals(MonitoringFailureKind.PROTOCOL_ERROR, failed.failureKind)
            assertEquals(MailProcessingState.PROCESSED, store.mails.single().processingState)
            server.rejectDeleteNumber = null
            assertTrue(check.check().success)
            assertEquals(1, alarms); assertEquals(1, store.mails.size); assertTrue(server.remaining().isEmpty())
        }
    }

    @Test fun handoffSaveAndProcessedCommitFailuresNeverEnterDeletion() = runBlocking {
        for (failure in listOf("handoff", "save", "finalize")) {
            Pop3TestServer().use { server ->
                val store = Store().apply { rejectSave = failure == "save"; rejectFinalize = failure == "finalize" }
                val check = coordinator(store, AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)), credentials, resolveDeletes = { _, deleted, absent -> deleted.size to absent.size }, localProcessedMails = { accountId -> store.mails.filter { it.accountId == accountId } })) {
                    if (failure == "handoff") error("Handoff failed")
                }
                assertFalse(check.check().success)
                assertFalse(server.commands.any { it.startsWith("DELE") })
                assertEquals(1, server.remaining().size)
                assertTrue(store.mails.all { it.processingState == MailProcessingState.RECEIVED })
            }
        }
    }

    @Test fun noMatchProcessedCanBeDeletedAndNeverRetainsAcrossPolls() = runBlocking {
        for (policy in listOf(ServerDeletePolicy.IMMEDIATELY, ServerDeletePolicy.NEVER)) {
            Pop3TestServer().use { server ->
                val store = Store()
                val check = coordinator(store, AccountMailReceiver(listOf(server.account(policy)), credentials, resolveDeletes = { _, deleted, absent -> deleted.size to absent.size }, localProcessedMails = { accountId -> store.mails.filter { it.accountId == accountId } }), emptyList()) { error("No match must not alarm") }
                assertTrue(check.check().success); assertTrue(check.check().success)
                assertEquals(1, store.mails.size); assertFalse(store.mails.single().alertTriggered)
                assertEquals(if (policy == ServerDeletePolicy.NEVER) 1 else 0, server.remaining().size)
                assertEquals(policy == ServerDeletePolicy.IMMEDIATELY, server.commands.any { it.startsWith("DELE") })
            }
        }
    }

    @Test fun connectionTestIsReadOnlyEvenForImmediatePolicy() = runBlocking {
        Pop3TestServer().use { server ->
            val account = server.account(ServerDeletePolicy.IMMEDIATELY)
            AccountMailReceiver(listOf(account), credentials).testConnection(account)
            assertFalse(server.commands.any { it.startsWith("DELE") })
            assertEquals(1, server.remaining().size)
        }
    }
}
