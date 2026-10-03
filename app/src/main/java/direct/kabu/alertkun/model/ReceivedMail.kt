package direct.kabu.alertkun.model

/**
 * A locally saved mail used for later rule processing.
 *
 * [remoteMessageId] is a POP3 UIDL or an IMAP UID, according to the owning account's protocol.
 */
data class ReceivedMail(
    val id: String,
    val accountId: String,
    val remoteMessageId: String,
    val receivedAtEpochMillis: Long,
    val from: String?,
    val headers: Map<String, String>,
    val subject: String?,
    val body: String?,
    val bodyFetched: Boolean,
    val processingState: MailProcessingState,
    val alertTriggered: Boolean,
    val matchedRuleId: String?,
)

enum class MailProcessingState {
    RECEIVED,
    PROCESSED,
}
