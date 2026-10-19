package direct.kabu.alertkun.mail.common

import direct.kabu.alertkun.mail.imap.ImapMailReceiver
import direct.kabu.alertkun.mail.pop3.Pop3MailReceiver
import direct.kabu.alertkun.mail.pop3.ServerDeleteEligibility
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import direct.kabu.alertkun.security.CredentialStore

/** Selects a protocol receiver outside MailCheckCoordinator and keeps account identity explicit. */
class AccountMailReceiver(
    accounts: List<MailAccount>,
    private val credentialStore: CredentialStore,
    private val observation: ObservationSink = { _, _ -> },
    private val localProcessedMails: (suspend (String) -> List<ReceivedMail>)? = null,
    private val resolveDeletes: (suspend (String, Set<String>, Set<String>) -> Pair<Int, Int>)? = null,
    private val temporaryDirectory: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "alertkun-pop3"),
    private val trace: (String) -> Unit = {},
) : MailReceiver {
    private val accountsById = accounts.filter { it.enabled }.associateBy { it.id }
    override suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
        for (account in accountsById.values) receiverFor(account).receive(knownIds, consume)
    }
    override suspend fun fetchNewMessages(): List<RemoteMail> = buildList { receive({ emptySet() }) { add(it) } }
    suspend fun testConnection(account: MailAccount) {
        receiverFor(account).testConnection()
    }

    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        accountsById.values.filter {
            it.serverDeletePolicy != ServerDeletePolicy.NEVER
        }.forEach { account ->
            val candidates = checkNotNull(localProcessedMails) { "Local deletion candidate source is required" }
                .invoke(account.id).asSequence().filter {
                    ServerDeleteEligibility.eligible(account.serverDeletePolicy, account.id, it.remoteMessageId, it, now)
                }.map { it.remoteMessageId }.toSet()
            val fields = mapOf("accountId" to account.id, "protocol" to account.protocol.name,
                "operation" to "SERVER_DELETE", "count" to candidates.size)
            runCatching { observation("DELETE_CANDIDATES", fields) }
            if (candidates.isEmpty()) {
                runCatching { observation("POP3_DELETE_SKIP", fields + ("reason" to "NO_DELETE_CANDIDATES")) }
            } else {
                // Candidates only gate the connection. The server session rechecks every UIDL and Room lookup.
                val persist = checkNotNull(resolveDeletes) { "Deletion resolution store is required" }
                val (deleted, absent) = when (val receiver = receiverFor(account)) {
                    is Pop3MailReceiver -> receiver.deleteCandidates(lookup, now, candidates)
                    is ImapMailReceiver -> receiver.deleteCandidates(lookup, now, candidates)
                    else -> error("Unsupported protocol receiver")
                }
                val (deletedCount, absentCount) = persist(account.id, deleted, absent)
                listOf("SERVER_DELETE_CONFIRMED" to deletedCount, "SERVER_UIDL_ABSENT" to absentCount).forEach { (reason, count) ->
                    if (count > 0) runCatching { observation("DELETE_CANDIDATES_RESOLVED", fields + mapOf("reason" to reason, "count" to count)) }
                }
            }
        }
    }

    private fun receiverFor(account: MailAccount): JakartaMailReceiver {
        val password = credentialStore.load(account.credentialKey)
            ?: throw MailReceiverException(MonitoringFailureKind.AUTHENTICATION_FAILURE)
        return when (account.protocol) {
            MailProtocol.POP3, MailProtocol.POP3S -> Pop3MailReceiver(account, password, trace, observation, temporaryDirectory)
            MailProtocol.IMAP4, MailProtocol.IMAPS -> ImapMailReceiver(account, password, observation)
        }
    }
}
