package direct.kabu.alertkun.mail.common

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local developer queue; it deliberately has no Room representation. */
class DummyMailReceiver private constructor() : MailReceiver {
    private val mutex = Mutex()
    private val queued = LinkedHashMap<String, RemoteMail>()
    private var failNextCheck = false

    suspend fun queueMatchingMail() = queue("ALERT test", "This is a dummy alert message.")
    suspend fun queueNonMatchingMail() = queue("Dummy test", "This message should not match an alert rule.")
    suspend fun queue(subject: String, body: String, remoteMessageId: String = UUID.randomUUID().toString()) {
        mutex.withLock { queued[remoteMessageId] = RemoteMail(DUMMY_ACCOUNT_ID, remoteMessageId, subject, "dummy@alertkun.invalid", body = body) }
    }
    suspend fun simulateNextFailure() { mutex.withLock { failNextCheck = true } }

    override suspend fun fetchNewMessages(): List<RemoteMail> = mutex.withLock {
        if (failNextCheck) {
            failNextCheck = false
            throw IllegalStateException("Dummy mail check failure")
        }
        queued.values.map { it.copy(body = null, bodyFetched = false) }
    }

    override suspend fun fetchBody(remoteMail: RemoteMail): String? = mutex.withLock { queued[remoteMail.remoteMessageId]?.body }

    /** A remote mailbox keeps the message; local UNIQUE storage prevents repeat delivery. */
    companion object {
        const val DUMMY_ACCOUNT_ID = "__alertkun_dummy_phase6__"
        val instance: DummyMailReceiver = DummyMailReceiver()
    }
}
