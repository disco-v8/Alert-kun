package direct.kabu.alertkun.model

/** An account used to receive monitoring emails. */
data class MailAccount(
    val id: String,
    val name: String,
    val protocol: MailProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val credentialKey: String,
    val deleteAfterReceive: Boolean,
    val trustedCertificateId: String?,
    val enabled: Boolean,
)

enum class MailProtocol {
    POP3,
    POP3S,
    IMAP4,
    IMAPS,
}
