package direct.kabu.alertkun.mail.imap

import direct.kabu.alertkun.mail.common.JakartaMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteIdentity
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import jakarta.mail.Folder
import jakarta.mail.Message
import org.eclipse.angus.mail.imap.IMAPFolder

class ImapMailReceiver(account: MailAccount, password: String, observation: ObservationSink = { _, _ -> }) : JakartaMailReceiver(account, password, observation) {
    override fun remoteMessageId(folder: Folder, message: Message): String {
        val imapFolder = folder as? IMAPFolder ?: throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
        val uidValidity = imapFolder.uidValidity
        val uid = imapFolder.getUID(message)
        return try {
            RemoteIdentity.imap(uidValidity, uid)
        } catch (_: IllegalArgumentException) {
            throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
        }
    }
}
