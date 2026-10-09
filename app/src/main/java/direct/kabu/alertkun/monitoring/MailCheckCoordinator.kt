package direct.kabu.alertkun.monitoring

import direct.kabu.alertkun.alarm.AlarmEvent
import direct.kabu.alertkun.model.AlertRule
import direct.kabu.alertkun.model.MailBackgroundColor
import direct.kabu.alertkun.model.MailProcessingState
import direct.kabu.alertkun.model.MailProcessingOutcome
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.mail.common.MailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.rules.RuleEngine
import direct.kabu.alertkun.rules.RuleEvaluation
import java.util.UUID
import kotlinx.coroutines.CancellationException

interface MonitoringMailStore {
    /** Missing/unsupported lookup retains remote mail. Production resolves account + UIDL from Room. */
    suspend fun findByRemoteId(accountId: String, remoteMessageId: String): ReceivedMail? = null
    suspend fun receivedMails(): List<ReceivedMail>
    /** false means a database unique constraint rejected an already received remote message. */
    suspend fun saveReceived(mail: ReceivedMail): Boolean
    suspend fun finalizeProcessing(id: String, alertTriggered: Boolean, matchedRuleId: String?, backgroundColor: MailBackgroundColor)
    /** Production must override this with one Room transaction for the complete poll. */
    suspend fun finalizeBatch(outcomes: List<MailProcessingOutcome>) {
        outcomes.forEach { finalizeProcessing(it.mailId, it.alertTriggered, it.matchedRuleId, it.backgroundColor) }
    }
}

interface MonitoringRuleSource { suspend fun allRules(): List<AlertRule> }
interface MonitoringAlarmSink { fun start(event: AlarmEvent) }

/** Performs one check. It is framework-free and therefore directly JVM-testable. */
class MailCheckCoordinator(
    private val mailStore: MonitoringMailStore,
    private val ruleSource: MonitoringRuleSource,
    private val receiver: MailReceiver,
    private val alarmSink: MonitoringAlarmSink,
    private val ruleEngine: RuleEngine = RuleEngine(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun check(): MailCheckResult = try {
        val rules = ruleSource.allRules()
        val evaluated = mutableListOf<EvaluatedMail>()
        var checked = 0
        var received = 0
        // Recovery always precedes remote work.
        mailStore.receivedMails().forEach { mail ->
            checked++
            evaluated += evaluate(mail, rules)
        }
        receiver.fetchNewMessages().forEach { remote ->
            val mail = receivedMail(remote, rules)
            if (mailStore.saveReceived(mail)) { // Room's unique index is the final duplicate guard.
                received++
                checked++
                evaluated += evaluate(mail, rules)
            }
        }
        val selected = evaluated.mapNotNull { it.candidate }.minWithOrNull(PendingAlarmCandidate.selectionOrder)
        // No per-mail handoff and no fallback queue. Failure leaves the whole batch RECEIVED.
        selected?.let { alarmSink.start(it.event) }
        mailStore.finalizeBatch(evaluated.map { result ->
            MailProcessingOutcome(result.mail.id, result.candidate != null && result.candidate == selected,
                result.rule?.id, result.rule?.backgroundColor ?: MailBackgroundColor.DEFAULT)
        })
        receiver.deleteProcessedMessages(mailStore::findByRemoteId, now())
        MailCheckResult(true, checked, received, if (selected == null) 0 else 1, selectedAlarm = selected?.event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MailReceiverException) {
        MailCheckResult(false, error = error, failureKind = error.failureKind)
    } catch (error: Throwable) {
        MailCheckResult(false, error = error, failureKind = MonitoringFailureKind.SERVER_UNAVAILABLE)
    }

    private suspend fun receivedMail(remote: RemoteMail, rules: List<AlertRule>): ReceivedMail {
        var candidate = remote
        if (ruleEngine.evaluate(candidate.subject, candidate.body, candidate.bodyFetched, rules) is RuleEvaluation.BodyRequired) {
            candidate = candidate.copy(body = receiver.fetchBody(candidate), bodyFetched = true)
        }
        return ReceivedMail(UUID.randomUUID().toString(), candidate.accountId, candidate.remoteMessageId, now(), candidate.from,
            candidate.headers, candidate.subject, candidate.body, candidate.bodyFetched, MailProcessingState.RECEIVED, false, null)
    }

    private fun evaluate(mail: ReceivedMail, rules: List<AlertRule>): EvaluatedMail {
        return when (val evaluation = ruleEngine.evaluate(mail.subject, mail.body, mail.bodyFetched, rules)) {
            is RuleEvaluation.Match -> {
                val rule = evaluation.rule
                val event = AlarmEvent(mail.id, rule.id, rule.name, mail.subject, rule.soundUri, rule.vibrationPattern, rule.alarmMode,
                    mailFrom = mail.from, receivedAtEpochMillis = mail.receivedAtEpochMillis, soundType = rule.soundType)
                EvaluatedMail(mail, rule, PendingAlarmCandidate(mail.id, mail.accountId, mail.remoteMessageId,
                    rule.id, rule.priority, mail.receivedAtEpochMillis, event))
            }
            RuleEvaluation.NoMatch -> EvaluatedMail(mail, null, null)
            RuleEvaluation.BodyRequired -> throw IllegalStateException("Received mail is missing body required by an enabled rule")
        }
    }

    private data class EvaluatedMail(val mail: ReceivedMail, val rule: AlertRule?, val candidate: PendingAlarmCandidate?)

}
