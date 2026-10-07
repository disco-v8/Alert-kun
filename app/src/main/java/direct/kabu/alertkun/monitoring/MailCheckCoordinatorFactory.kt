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
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.security.AndroidKeystoreCredentialStore
import kotlinx.coroutines.flow.first

internal object MailCheckCoordinatorFactory {
    suspend fun create(context: Context): MailCheckCoordinator {
        val database = AlertKunDatabase.getInstance(context)
        val mails = MailRepository(database.receivedMailDao())
        val accounts = AccountRepository(database.accountDao()).accounts.first().filter { it.enabled }
        return MailCheckCoordinator(
            mailStore = object : MonitoringMailStore {
                override suspend fun finalizeBatch(outcomes: List<direct.kabu.alertkun.model.MailProcessingOutcome>) = mails.finalizeBatch(outcomes)
                override suspend fun findByRemoteId(accountId: String, remoteMessageId: String) = mails.getByRemoteId(accountId, remoteMessageId)
                override suspend fun receivedMails() = mails.receivedMails()
                override suspend fun saveReceived(mail: ReceivedMail): Boolean = try { mails.saveReceived(mail); true } catch (_: android.database.sqlite.SQLiteConstraintException) { false }
                override suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor) = mails.finalizeProcessing(id, alertTriggered, matchedRuleId, backgroundColor)
            },
            ruleSource = object : MonitoringRuleSource { override suspend fun allRules() = RuleRepository(database.ruleDao()).allRules() },
            receiver = if (accounts.isEmpty()) DummyMailReceiver.instance else AccountMailReceiver(accounts, AndroidKeystoreCredentialStore(context)),
            alarmSink = object : MonitoringAlarmSink { override fun start(event: AlarmEvent) { AlarmController.getInstance(context).start(event) } },
        )
    }
}
