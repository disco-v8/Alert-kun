package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.JakartaMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteIdentity
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import jakarta.mail.Folder
import jakarta.mail.Message
import org.eclipse.angus.mail.pop3.POP3Folder

class Pop3MailReceiver(account: MailAccount, password: String, trace: (String) -> Unit = {},
    observation: ObservationSink = { _, _ -> }) : JakartaMailReceiver(account, password, observation) {
    private val deletion = Pop3ServerDeletion(account, password, trace, observation = observation)

    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        deletion.deleteProcessed(lookup, now)
    }

    internal suspend fun deleteCandidates(lookup: suspend (String, String) -> ReceivedMail?, now: Long,
        candidates: Set<String>) = deletion.deleteProcessed(lookup, now, candidates)

    override fun remoteMessageId(folder: Folder, message: Message): String =
        try {
            RemoteIdentity.pop3((folder as? POP3Folder)?.getUID(message))
        } catch (_: IllegalArgumentException) {
            throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
        }
}
