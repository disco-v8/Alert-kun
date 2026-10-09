package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.monitoring.ObservationSink
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
    private val observation: ObservationSink = { _, _ -> },
) : MailReceiver {
    protected abstract fun remoteMessageId(folder: Folder, message: Message): String

    override suspend fun fetchNewMessages(): List<RemoteMail> = withContext(Dispatchers.IO) {
        connectAndUse("READ_HEADERS") { folder ->
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
        connectAndUse("READ_BODY") { folder ->
            folder.messages.firstOrNull { remoteMessageId(folder, it) == remoteMail.remoteMessageId }
                ?.let(MailTextExtractor::extract)
        }
    }

    suspend fun testConnection() = withContext(Dispatchers.IO) { connectAndUse("CONNECTION_TEST") { } }

    private fun <T> connectAndUse(operation: String, block: (Folder) -> T): T {
        var store: Store? = null
        var folder: Folder? = null
        var connected = false
        var connectStarted: Long? = null
        fun observe(event: String, category: String? = null, exceptionClass: String? = null) {
            runCatching { observation(event, mapOf("accountId" to account.id, "protocol" to account.protocol.name,
                "operation" to operation, "elapsedMs" to connectStarted?.let { System.nanoTime() / 1_000_000L - it },
                "category" to category, "exceptionClass" to exceptionClass)) }
        }
        try {
            val session = Session.getInstance(MailConnectionSettings.properties(account))
            store = session.getStore(MailConnectionSettings.protocolName(account.protocol))
            connectStarted = System.nanoTime() / 1_000_000L
            observe("MAIL_CONNECT_START")
            store.connect(account.host, account.port, account.username, password)
            connected = true
            observe("MAIL_CONNECT_SUCCESS")
            folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            return block(folder)
        } catch (cancelled: CancellationException) {
            observe("MAIL_CONNECT_CANCELLED")
            throw cancelled
        } catch (error: Throwable) {
            observe(if (connected) "MAIL_SESSION_FAILURE" else "MAIL_CONNECT_FAILURE",
                MailConnectionSettings.classify(error).name, error.javaClass.simpleName)
            throw MailReceiverException(MailConnectionSettings.classify(error), error)
        } finally {
            runCatching { if (folder?.isOpen == true) folder.close(false) }
            runCatching { store?.close() }
        }
    }
}
