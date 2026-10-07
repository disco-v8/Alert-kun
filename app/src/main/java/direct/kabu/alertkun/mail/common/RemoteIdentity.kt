package direct.kabu.alertkun.mail.common

/** Stable local representations of protocol-specific remote identities. */
object RemoteIdentity {
    fun pop3(uidl: String?): String = uidl?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("POP3 UIDL is required")

    fun imap(uidValidity: Long, uid: Long): String {
        require(uidValidity > 0L)
        require(uid > 0L)
        return "$uidValidity:$uid"
    }
}
