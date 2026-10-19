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
    val unsupported: Boolean = false,
)

interface MailReceiver {
    /** The callback completes local save/evaluation before the next message is acquired. */
    suspend fun receive(knownIds: suspend (String) -> Set<String>, consume: suspend (RemoteMail) -> Unit) {
        // Compatibility for in-memory receivers/test doubles. Network receivers override this.
        val known = mutableMapOf<String, Set<String>>()
        for (mail in fetchNewMessages()) {
            val ids = known.getOrPut(mail.accountId) { knownIds(mail.accountId) }
            if (mail.remoteMessageId !in ids) consume(mail.copy(bodyFetched = true))
        }
    }
    /** Bounds reusable read-only resources; closes before alarm handoff and server deletion. */
    suspend fun <T> withReadSession(block: suspend () -> T): T = block()
    suspend fun fetchNewMessages(): List<RemoteMail> = error("Use the streaming receive callback")
    /** POP3 can exclude known identities before touching message headers. */
    suspend fun fetchNewMessages(isKnown: suspend (String, String) -> Boolean): List<RemoteMail> = fetchNewMessages()
    suspend fun fetchBody(remoteMail: RemoteMail): String? = error("Body retrieval is part of receive")
    /** Called only after the entire local processing pass succeeds. Unsupported protocols retain mail. */
    suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {}
}
