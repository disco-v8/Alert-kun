package direct.kabu.alertkun.ui.mail

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.monitoring.MailCheckCoordinator
import direct.kabu.alertkun.monitoring.MonitoringAlarmSink
import direct.kabu.alertkun.monitoring.MonitoringMailStore
import direct.kabu.alertkun.monitoring.MonitoringRuleSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReceivedMailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun noRulesHeaderOnlyMailFlowsThroughRoomViewModelAndScreenWithoutDuplicates() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val repository = MailRepository(database.receivedMailDao())
            val account = MailAccount(UUID.randomUUID().toString(), "Test", MailProtocol.POP3S,
                "example.invalid", 995, "tester", "test-key", false, null, true)
            val accounts = AccountRepository(database.accountDao())
            runBlocking { accounts.upsert(account) }
            val snapshot = runBlocking { accounts.get(account.id) }!!
            val remote = RemoteMail(snapshot.id, "dovecot-uidl-1", "Header-only test", "sender@example.invalid",
                headers = mapOf("Message-ID" to "<test@example.invalid>", "Date" to "123"))
            val now = System.currentTimeMillis()
            var alarmCalls = 0
            var bodyFetchCalls = 0
            val coordinator = MailCheckCoordinator(
                mailStore = object : MonitoringMailStore {
                    override suspend fun receivedMails() = repository.receivedMails()
                    override suspend fun saveReceived(mail: ReceivedMail): Boolean {
                        assertEquals(MailProcessingState.RECEIVED, mail.processingState)
                        return try {
                            repository.saveReceived(mail)
                            val row = database.receivedMailDao().getById(mail.id)!!
                            assertEquals(MailProcessingState.RECEIVED, row.processingState)
                            assertEquals(snapshot.id, row.accountId)
                            assertEquals(remote.remoteMessageId, row.remoteMessageId)
                            true
                        } catch (_: SQLiteConstraintException) { false }
                    }
                    override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) =
                        repository.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
                },
                ruleSource = object : MonitoringRuleSource { override suspend fun allRules() = emptyList<direct.kabu.alertkun.model.AlertRule>() },
                receiver = object : MailReceiver {
                    override suspend fun fetchNewMessages() = listOf(remote)
                    override suspend fun fetchBody(remoteMail: RemoteMail): String? { bodyFetchCalls++; error("Body must not be fetched") }
                },
                alarmSink = object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { alarmCalls++ } },
                now = { now },
            )
            val vm = ReceivedMailViewModel(application, repository)
            compose.setContent { ReceivedMailScreen({}, viewModel = vm) }
            compose.waitUntil(5_000) { !vm.state.value.loading }
            compose.onNodeWithTag("received_mail_empty").assertIsDisplayed()
            val result = runBlocking { coordinator.check() }
            assertTrue(result.success)
            assertEquals(1, result.receivedCount)
            compose.waitUntil(5_000) { vm.state.value.mails.size == 1 && vm.state.value.mails.single().processingState == MailProcessingState.PROCESSED }
            val saved = vm.state.value.mails.single()
            assertEquals(snapshot.id, saved.accountId)
            assertEquals(remote.remoteMessageId, saved.remoteMessageId)
            assertEquals(remote.subject, saved.subject)
            assertEquals(remote.from, saved.from)
            assertEquals(remote.headers, saved.headers)
            assertEquals(now, saved.receivedAtEpochMillis)
            assertFalse(saved.bodyFetched)
            assertNull(saved.body)
            assertFalse(saved.alertTriggered)
            assertNull(saved.matchedRuleId)
            assertEquals(0, alarmCalls)
            assertEquals(0, bodyFetchCalls)
            compose.onNodeWithTag("received_mail_${saved.id}").assertIsDisplayed()
            val repeated = runBlocking { coordinator.check() }
            assertTrue(repeated.success)
            assertEquals(0, repeated.receivedCount)
            assertEquals(1, runBlocking { repository.mails.first() }.size)
            assertEquals(0, alarmCalls)
        } finally { database.close() }
    }

    @Test fun allAccountsAndPendingAndProcessedNonAlertsRemainVisibleWithinRetentionPeriod() {
        val database = Room.inMemoryDatabaseBuilder(application, AlertKunDatabase::class.java).build()
        try {
            val repository = MailRepository(database.receivedMailDao())
            val now = System.currentTimeMillis()
            val pending = ReceivedMail("pending", "account-a", "shared-uidl", now, null, emptyMap(),
                "Pending", null, false, MailProcessingState.RECEIVED, false, null)
            val processed = pending.copy(id = "processed", accountId = "account-b", receivedAtEpochMillis = now - 60_000)
            runBlocking {
                repository.saveReceived(pending)
                repository.saveReceived(processed)
                repository.finalizeProcessing(processed.id, false, null)
            }
            val vm = ReceivedMailViewModel(application, repository)
            compose.setContent { ReceivedMailScreen({}, viewModel = vm) }
            compose.waitUntil(5_000) { vm.state.value.mails.size == 2 }
            assertEquals(listOf("pending", "processed"), vm.state.value.mails.map { it.id })
            compose.onNodeWithTag("received_mail_pending").assertIsDisplayed()
            compose.onNodeWithTag("received_mail_processed").assertIsDisplayed()
        } finally { database.close() }
    }
}
