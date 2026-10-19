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

/** Connection ownership never crosses a receiving callback/session boundary. */
abstract class JakartaMailReceiver(
    protected val account: MailAccount,
    private val password: String,
    private val observation: ObservationSink = { _, _ -> },
) : MailReceiver {
    protected abstract suspend fun receiveMessages(folder: Folder, knownIds: Set<String>, consume: suspend (RemoteMail) -> Unit)
    protected abstract fun remoteMessageId(folder: Folder, message: Message): String

    override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) =
        withContext(Dispatchers.IO) {
            val known = knownIds(account.id)
            connectAndUse("RECEIVE") { folder -> receiveMessages(folder, known, consume) }
        }

    // Collection convenience for existing local connection probes; production uses receive().
    override suspend fun fetchNewMessages(): List<RemoteMail> = buildList {
        receive({ emptySet() }) { add(it) }
    }
    override suspend fun fetchNewMessages(isKnown: suspend (String, String) -> Boolean): List<RemoteMail> =
        error("Use account-scoped known IDs and receive")

    suspend fun testConnection() = withContext(Dispatchers.IO) { connectAndUse("CONNECTION_TEST") { } }

    protected suspend fun <T> connectAndUse(operation: String, mode: Int = Folder.READ_ONLY, block: suspend (Folder) -> T): T {
        var store: Store? = null
        var folder: Folder? = null
        var connected = false
        val started = System.nanoTime() / 1_000_000L
        fun observe(event: String, failure: Throwable? = null) {
            runCatching { observation(event, mapOf("accountId" to account.id, "protocol" to account.protocol.name,
                "operation" to operation, "elapsedMs" to System.nanoTime() / 1_000_000L - started,
                "category" to failure?.let { MailConnectionSettings.classify(it).name },
                "exceptionClass" to failure?.javaClass?.simpleName)) }
        }
        try {
            store = Session.getInstance(MailConnectionSettings.properties(account))
                .getStore(MailConnectionSettings.protocolName(account.protocol))
            observe("MAIL_CONNECT_START")
            store.connect(account.host, account.port, account.username, password)
            connected = true
            observe("MAIL_CONNECT_SUCCESS")
            folder = store.getFolder("INBOX")
            folder.open(mode)
            return block(folder)
        } catch (cancelled: CancellationException) {
            observe("MAIL_CONNECT_CANCELLED")
            throw cancelled
        } catch (local: LocalMailProcessingException) {
            throw local
        } catch (failure: Exception) {
            observe(if (connected) "MAIL_SESSION_FAILURE" else "MAIL_CONNECT_FAILURE", failure)
            throw MailReceiverException(MailConnectionSettings.classify(failure), failure)
        } finally {
            // false is essential after uncertain IMAP flag/expunge results, too.
            runCatching { if (folder?.isOpen == true) folder.close(false) }
            runCatching { store?.close() }
        }
    }
}
