package direct.kabu.alertkun.ui.mail

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import direct.kabu.alertkun.MainActivity
import direct.kabu.alertkun.R
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.mail.pop3.Pop3MailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3TestServer
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.monitoring.*
import direct.kabu.alertkun.ui.main.formatMonitoringTimestamp
import jakarta.mail.internet.MimeUtility
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real TCP receiver, Room and production navigation. Only disposable fixture rows are added/removed.
 * No user account, credential, monitoring setting, schedule or real alarm output is changed.
 */
class SenderMailNavigationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun createSenderRuleReceiveMatchOpenDetailsAndBackThenReadFetchedBody() = runBlocking {
        val database = AlertKunDatabase.getInstance(app)
        val repository = MailRepository(database.receivedMailDao())
        val rules = RuleRepository(database.ruleDao())
        val unique = UUID.randomUUID().toString()
        val ruleName = "Sender fixture $unique"
        val accountId = "sender-fixture-$unique"
        val senderName = "監視担当 $unique"
        val displayFrom = "\"$senderName\" <fixture@example.invalid>"
        var fixtureRuleId: String? = null
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                compose.onNodeWithTag("main_open_rules").performClick()
                compose.onNodeWithTag("rule_add").performClick()
                compose.onNodeWithTag("rule_name").performTextInput(ruleName)
                compose.onNodeWithTag("rule_search_text").performTextInput(senderName)
                compose.onNodeWithTag("rule_target").performClick()
                compose.onNodeWithTag("rule_target_SENDER").performClick()
                compose.onNodeWithTag("rule_target").assertTextContains(app.getString(R.string.target_sender))
                compose.onNodeWithTag("rule_save").performScrollTo().performClick()
                compose.waitUntil(5_000) { runBlocking { rules.allRules().any { it.name == ruleName } } }
                val senderRule = rules.allRules().single { it.name == ruleName }
                fixtureRuleId = senderRule.id
                assertEquals(RuleTarget.SENDER, rules.get(senderRule.id)!!.target)
                Espresso.pressBack()
                compose.onNodeWithTag("main_open_mail").performClick()
                Pop3TestServer(listOf("sender-mail")).use { server ->
                    server.subjects = mapOf("sender-mail" to "Sender match fixture")
                    server.fromHeaders = mapOf("sender-mail" to
                        "${MimeUtility.encodeText(senderName, "UTF-8", "B")} <fixture@example.invalid>")
                    val account = server.account(ServerDeletePolicy.NEVER).copy(id = accountId)
                    val matched = receive(repository, account, senderRule)
                    val commandsBeforeDisplay = server.commands.size
                    assertTrue(matched.alertTriggered)
                    assertEquals(senderRule.id, matched.matchedRuleId)
                    assertEquals(displayFrom, matched.from)
                    assertFalse(matched.bodyFetched)
                    openNewestMail(matched.id)
                    compose.onNodeWithTag("mail_detail_subject").assertTextEquals("Sender match fixture")
                    compose.onNodeWithTag("mail_detail_from").assertTextEquals(app.getString(R.string.received_mail_from, displayFrom))
                    compose.onNodeWithTag("mail_detail_timestamp").assertTextEquals(app.getString(R.string.received_mail_timestamp,
                        formatMonitoringTimestamp(matched.receivedAtEpochMillis)))
                    compose.onNodeWithTag("mail_detail_body").assertTextEquals(app.getString(R.string.received_mail_no_body))
                    Espresso.pressBack()
                    compose.onNodeWithTag("received_mail_list").assertIsDisplayed()
                    assertEquals(commandsBeforeDisplay, server.commands.size)
                    assertFalse(server.commands.any { it.startsWith("DELE") })
                }
                // The existing receiver fetches text only when a body rule requires it.
                Pop3TestServer(listOf("body-mail")).use { server ->
                    server.subjects = mapOf("body-mail" to "Saved multiline body fixture")
                    server.bodies = mapOf("body-mail" to "Saved body fixture\r\nSecond line\r\nThird line")
                    val account = server.account(ServerDeletePolicy.NEVER).copy(id = accountId)
                    val bodyRule = senderRule.copy(target = RuleTarget.BODY, searchText = "Saved body fixture")
                    val saved = receive(repository, account, bodyRule)
                    val commandsBeforeDisplay = server.commands.size
                    assertTrue(saved.bodyFetched)
                    assertTrue(saved.body!!.contains("\nSecond line"))
                    openNewestMail(saved.id)
                    compose.onNodeWithTag("mail_detail_body").assertTextEquals(saved.body).assertIsDisplayed()
                    // Recreation keeps the route argument and reloads saved data without a mail connection.
                    scenario.recreate()
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("mail_detail_body").fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithTag("mail_detail_body").assertTextEquals(saved.body).assertIsDisplayed()
                    compose.onNodeWithTag("mail_detail_back").performClick()
                    compose.onNodeWithTag("received_mail_list").assertIsDisplayed()
                    assertEquals(commandsBeforeDisplay, server.commands.size)
                    assertFalse(server.commands.any { it.startsWith("DELE") })
                }
            }
        } finally {
            // A failed UI assertion may occur after Save but before fixtureRuleId is assigned.
            (fixtureRuleId ?: rules.allRules().firstOrNull { it.name == ruleName }?.id)?.let { rules.delete(it) }
            database.openHelper.writableDatabase.execSQL("DELETE FROM received_mails WHERE accountId = ?", arrayOf(accountId))
        }
    }

    private fun openNewestMail(id: String) {
        compose.onNodeWithTag("received_mail_list").performScrollToIndex(0)
        // Room invalidation and the lifecycle-aware UI collector complete asynchronously.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("received_mail_$id").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("received_mail_$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mail_detail_body").fetchSemanticsNodes().size == 1 }
    }

    private suspend fun receive(repository: MailRepository, account: MailAccount, rule: AlertRule): ReceivedMail {
        val store = object : MonitoringMailStore {
            override suspend fun receivedMails() = emptyList<ReceivedMail>()
            override suspend fun saveReceived(mail: ReceivedMail): Boolean { repository.saveReceived(mail); return true }
            override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) =
                repository.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
            override suspend fun finalizeBatch(outcomes: List<MailProcessingOutcome>) = repository.finalizeBatch(outcomes)
        }
        val handoffs = mutableListOf<AlarmEvent>()
        val coordinator = MailCheckCoordinator(store, object : MonitoringRuleSource {
            override suspend fun allRules() = listOf(rule)
        }, Pop3MailReceiver(account, "fixture-secret"), object : MonitoringAlarmSink {
            override fun start(event: AlarmEvent) { handoffs += event }
        })
        val result = coordinator.check()
        assertTrue("Disposable mail check failed: ${result.error?.javaClass?.simpleName}", result.success)
        assertEquals(1, handoffs.size)
        assertEquals(rule.id, result.selectedAlarm!!.ruleId)
        return repository.getByRemoteId(account.id, if (rule.target == RuleTarget.SENDER) "sender-mail" else "body-mail")!!
    }
}
