package direct.kabu.alertkun.monitoring

import android.content.Context
import direct.kabu.alertkun.alarm.AlarmController
import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.data.database.AlertKunDatabase
import direct.kabu.alertkun.data.repository.MailRepository
import direct.kabu.alertkun.data.repository.RuleRepository
import direct.kabu.alertkun.data.repository.AccountRepository
import direct.kabu.alertkun.mail.common.AccountMailReceiver
import direct.kabu.alertkun.mail.common.DummyMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.mail.common.MailReceiverSelection
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import kotlinx.coroutines.flow.first

internal object MailCheckCoordinatorFactory {
    suspend fun select(context: Context, demoModeEnabled: Boolean): MailReceiverSelection =
        MailReceiverSelection(demoModeEnabled, AccountRepository(AlertKunDatabase.getInstance(context).accountDao()).accounts.first())

    fun create(context: Context, selection: MailReceiverSelection, network: NetworkStabilization?, observation: ObservationSink = { _, _ -> }): MailCheckCoordinator {
        val database = AlertKunDatabase.getInstance(context)
        val mails = MailRepository(database.receivedMailDao())
        val accounts = selection.accounts
        val receiver = checkNotNull(selection.receiver(real = { enabledAccounts ->
            NetworkStabilizingMailReceiver(AccountMailReceiver(enabledAccounts, AndroidKeystoreCredentialStore(context), observation = observation,
            localProcessedMails = mails::deleteCandidatesForAccount, resolveDeletes = mails::resolveDeletes) {
            monitoringDebugLog(context, it)
        }, checkNotNull(network), enabledAccounts.any { it.protocol in listOf(direct.kabu.alertkun.model.MailProtocol.POP3, direct.kabu.alertkun.model.MailProtocol.POP3S)
            && it.serverDeletePolicy != direct.kabu.alertkun.model.ServerDeletePolicy.NEVER })
        }, demo = { DummyMailReceiver.instance }))
        return MailCheckCoordinator(
            mailStore = object : MonitoringMailStore {
                override suspend fun finalizeBatch(outcomes: List<direct.kabu.alertkun.model.MailProcessingOutcome>) {
                    monitoringDebugLog(context, "Room finalize batch start count=${outcomes.size}")
                    mails.finalizeBatch(outcomes)
                    monitoringDebugLog(context, "Room finalize batch completed count=${outcomes.size}")
                }
                override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = mails.getByRemoteId(accountId, remoteMessageId)
                override suspend fun receivedMails() = mails.receivedMails().filter {
                    if (selection.mode == direct.kabu.alertkun.mail.common.MailCheckMode.DEMO)
                        it.accountId == DummyMailReceiver.DUMMY_ACCOUNT_ID
                    else it.accountId != DummyMailReceiver.DUMMY_ACCOUNT_ID
                }
                override suspend fun saveReceived(mail: ReceivedMail): Boolean = try { mails.saveReceived(mail); true } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
                override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) = mails.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
            },
            ruleSource = object : MonitoringRuleSource { override suspend fun allRules() = RuleRepository(database.ruleDao()).allRules() },
            receiver = object : MailReceiver by receiver {
                override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
                    monitoringDebugLog(context, "server delete check start policies=${accounts.map { it.serverDeletePolicy }.distinct()}")
                    try {
                        receiver.deleteProcessedMessages(lookup, now)
                        monitoringDebugLog(context, "server delete check completed")
                    } catch (error: Throwable) {
                        monitoringDebugLog(context, "server delete check exception type=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName} location=${error.cause?.stackTrace?.firstOrNull()}")
                        throw error
                    }
                }
                override suspend fun fetchNewMessages(): List<RemoteMail> {
                    monitoringDebugLog(context, "remote fetch start accounts=${accounts.size} protocols=${accounts.map { it.protocol }.distinct()}")
                    try {
                        val messages = receiver.fetchNewMessages()
                        monitoringDebugLog(context, "remote fetch completed messages=${messages.size}")
                        return messages
                    } catch (error: Throwable) {
                        monitoringDebugLog(context, "remote fetch exception type=${error.javaClass.simpleName}")
                        throw error
                    }
                }
            },
            alarmSink = object : MonitoringAlarmSink {
                override fun start(event: AlarmEvent) {
                    monitoringDebugLog(context, "alarm handoff start mailId=${event.mailId} ruleId=${event.ruleId}")
                    AlarmController.getInstance(context).start(event)
                    monitoringDebugLog(context, "alarm handoff accepted mailId=${event.mailId} ruleId=${event.ruleId}")
                }
            },
        )
    }
}
