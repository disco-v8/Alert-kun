package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.model.ReceivedMail

/** Protocol-neutral message data. Future receivers supply a stable remote identifier. */
data class RemoteMail(
    val accountId: String,
    val remoteMessageId: String,
    val subject: String?,
    val from: String?,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyFetched: Boolean = false,
)

interface MailReceiver {
    suspend fun fetchNewMessages(): List<RemoteMail>
    suspend fun fetchBody(remoteMail: RemoteMail): String?
    /** Called only after the entire local processing pass succeeds. Unsupported protocols retain mail. */
    suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {}
}
