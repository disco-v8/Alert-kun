package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Store
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Properties
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared Jakarta Mail connection handling. Standard TLS hostname and trust-store validation remain enabled. */
abstract class JakartaMailReceiver(
    protected val account: MailAccount,
    private val password: String,
) : MailReceiver {
    protected abstract fun remoteMessageId(folder: Folder, message: Message): String

    override suspend fun fetchNewMessages(): List<RemoteMail> = withContext(Dispatchers.IO) {
        connectAndUse { folder ->
            folder.messages.map { message ->
                RemoteMail(
                    accountId = account.id,
                    remoteMessageId = remoteMessageId(folder, message),
                    subject = message.subject,
                    from = message.from?.joinToString(", "),
                    headers = buildMap {
                        message.getHeader("Message-ID")?.firstOrNull()?.let { put("Message-ID", it) }
                        message.sentDate?.time?.let { put("Date", it.toString()) }
                    },
                )
            }
        }
    }

    override suspend fun fetchBody(remoteMail: RemoteMail): String? = withContext(Dispatchers.IO) {
        require(remoteMail.accountId == account.id)
        connectAndUse { folder ->
            folder.messages.firstOrNull { remoteMessageId(folder, it) == remoteMail.remoteMessageId }
                ?.let(MailTextExtractor::extract)
        }
    }

    suspend fun testConnection() = withContext(Dispatchers.IO) { connectAndUse { } }

    private fun <T> connectAndUse(block: (Folder) -> T): T {
        var store: Store? = null
        var folder: Folder? = null
        try {
            val session = Session.getInstance(connectionProperties())
            store = session.getStore(protocolName())
            store.connect(account.host, account.port, account.username, password)
            folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            return block(folder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MailReceiverException(classify(error), error)
        } finally {
            runCatching { if (folder?.isOpen == true) folder.close(false) }
            runCatching { store?.close() }
        }
    }

    private fun connectionProperties(): Properties = Properties().apply {
        val protocol = protocolName()
        put("mail.store.protocol", protocol)
        put("mail.$protocol.connectiontimeout", TIMEOUT_MILLIS.toString())
        put("mail.$protocol.timeout", TIMEOUT_MILLIS.toString())
        put("mail.$protocol.writetimeout", TIMEOUT_MILLIS.toString())
        put("mail.$protocol.starttls.enable", "false")
        if (account.protocol == MailProtocol.POP3S || account.protocol == MailProtocol.IMAPS) {
            put("mail.$protocol.ssl.enable", "true")
            put("mail.$protocol.ssl.checkserveridentity", "true")
        }
    }

    private fun protocolName(): String = when (account.protocol) {
        MailProtocol.POP3 -> "pop3"
        MailProtocol.POP3S -> "pop3s"
        MailProtocol.IMAP4 -> "imap"
        MailProtocol.IMAPS -> "imaps"
    }

    private fun classify(error: Throwable): MonitoringFailureKind = when {
        error is MailReceiverException -> error.failureKind
        error is UnknownHostException -> MonitoringFailureKind.DNS_FAILURE
        error is SocketTimeoutException -> MonitoringFailureKind.CONNECTION_TIMEOUT
        error is ConnectException -> MonitoringFailureKind.CONNECTION_REFUSED
        error is SSLException || error.cause is CertificateException -> MonitoringFailureKind.TLS_FAILURE
        error is AuthenticationFailedException -> MonitoringFailureKind.AUTHENTICATION_FAILURE
        error.cause != null && error.cause !== error -> classify(error.cause!!)
        error is MessagingException -> MonitoringFailureKind.PROTOCOL_ERROR
        else -> MonitoringFailureKind.SERVER_UNAVAILABLE
    }

    private companion object { const val TIMEOUT_MILLIS = 30_000 }
}
