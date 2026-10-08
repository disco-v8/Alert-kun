package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
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
            val session = Session.getInstance(MailConnectionSettings.properties(account))
            store = session.getStore(MailConnectionSettings.protocolName(account.protocol))
            store.connect(account.host, account.port, account.username, password)
            folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            return block(folder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MailReceiverException(MailConnectionSettings.classify(error), error)
        } finally {
            runCatching { if (folder?.isOpen == true) folder.close(false) }
            runCatching { store?.close() }
        }
    }
}
