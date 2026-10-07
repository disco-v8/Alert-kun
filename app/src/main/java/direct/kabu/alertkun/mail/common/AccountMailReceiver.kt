package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.mail.imap.ImapMailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3MailReceiver
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.security.CredentialStore

/** Selects a protocol receiver outside MailCheckCoordinator and keeps account identity explicit. */
class AccountMailReceiver(
    accounts: List<MailAccount>,
    private val credentialStore: CredentialStore,
) : MailReceiver {
    private val accountsById = accounts.filter { it.enabled }.associateBy { it.id }

    override suspend fun fetchNewMessages(): List<RemoteMail> = buildList {
        accountsById.values.forEach { account -> addAll(receiverFor(account).fetchNewMessages()) }
    }

    override suspend fun fetchBody(remoteMail: RemoteMail): String? =
        accountsById[remoteMail.accountId]?.let { receiverFor(it).fetchBody(remoteMail) }

    suspend fun testConnection(account: MailAccount) {
        receiverFor(account).testConnection()
    }

    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        accountsById.values.filter {
            it.protocol in listOf(MailProtocol.POP3, MailProtocol.POP3S) && it.serverDeletePolicy != ServerDeletePolicy.NEVER
        }.forEach { receiverFor(it).deleteProcessedMessages(lookup, now) }
    }

    private fun receiverFor(account: MailAccount): JakartaMailReceiver {
        val password = credentialStore.load(account.credentialKey)
            ?: throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
        return when (account.protocol) {
            MailProtocol.POP3, MailProtocol.POP3S -> Pop3MailReceiver(account, password)
            MailProtocol.IMAP4, MailProtocol.IMAPS -> ImapMailReceiver(account, password)
        }
    }
}
