package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.mail.pop3.Pop3MailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.rules.RuleEngine
import direct.kabu.alertkun.rules.RuleEvaluation
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SenderRuleReceptionTest {
    @Test fun encodedFromIsDecodedBeforeSavingAndMatchingWithoutFetchingBody() = runBlocking {
        Pop3TestServer().use { server ->
            val displayName = "監視担当"
            val encoded = MimeUtility.encodeText(displayName, "UTF-8", "B")
            server.fromHeaders = mapOf("test-uid-1" to "$encoded <sender@example.invalid>")
            val receiver = Pop3MailReceiver(server.account(ServerDeletePolicy.NEVER), "fixture-secret")
            val rules = listOf(AlertRule("sender", "Sender", displayName, RuleTarget.SENDER, 1,
                null, VibrationPattern.NONE, AlarmMode.ONCE, true))
            val store = Store()
            val alarms = mutableListOf<AlarmEvent>()
            var bodyCalls = 0
            val coordinator = MailCheckCoordinator(store, object : MonitoringRuleSource {
                override suspend fun allRules() = rules
            }, object : MailReceiver {
                override suspend fun fetchNewMessages() = receiver.fetchNewMessages()
                override suspend fun fetchBody(remoteMail: RemoteMail): String? { bodyCalls++; error("Sender must not fetch body") }
            }, object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { alarms += event } })
            assertTrue(coordinator.check().success)
            val saved = store.mails.single()
            assertEquals("\"監視担当\" <sender@example.invalid>", saved.from)
            assertFalse(saved.from!!.contains("=?"))
            assertEquals("sender", saved.matchedRuleId)
            assertTrue(saved.alertTriggered)
            assertEquals(1, alarms.size)
            assertEquals(0, bodyCalls)
            assertEquals(MailProcessingState.PROCESSED, saved.processingState)
            assertTrue(coordinator.check().success)
            assertEquals(1, store.mails.size)
            assertEquals(1, alarms.size)
            // Recovery evaluates the same persisted From, with no second body retrieval.
            store.mails[0] = saved.copy(processingState = MailProcessingState.RECEIVED, alertTriggered = false, matchedRuleId = null)
            assertTrue(coordinator.check().success)
            assertEquals("sender", store.mails.single().matchedRuleId)
            assertEquals(0, bodyCalls)
            assertFalse(server.commands.any { it.startsWith("DELE") })
            assertTrue(RuleEngine().evaluate(null, null, false, rules, saved.from) is RuleEvaluation.Match)
        }
    }

    private class Store : MonitoringMailStore {
        val mails = mutableListOf<ReceivedMail>()
        override suspend fun receivedMails() = mails.filter { it.processingState == MailProcessingState.RECEIVED }
        override suspend fun saveReceived(mail: ReceivedMail): Boolean {
            if (mails.any { it.accountId == mail.accountId && it.remoteMessageId == mail.remoteMessageId }) return false
            mails += mail
            return true
        }
        override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) {
            val index = mails.indexOfFirst { it.id == id }
            mails[index] = mails[index].copy(processingState = MailProcessingState.PROCESSED,
                alertTriggered = alertTriggered, matchedRuleId = matchedRuleId, backgroundColor = backgroundColor)
        }
    }
}
