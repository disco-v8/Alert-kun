package direct.kabu.alertkun.mail.imap

import direct.kabu.alertkun.mail.common.JakartaMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteIdentity
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.mail.pop3.ServerDeleteEligibility
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.FetchProfile
import jakarta.mail.UIDFolder
import jakarta.mail.Flags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder


class ImapMailReceiver(account: MailAccount, password: String, observation: ObservationSink = { _, _ -> }) : JakartaMailReceiver(account, password, observation) {
    override suspend fun receiveMessages(folder: Folder, knownIds: Set<String>, consume: suspend (RemoteMail) -> Unit) {
        val imap = folder as IMAPFolder
        val messages = imap.messages
        val validity = imap.uidValidity
        imap.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        val identified = messages.map { it to RemoteIdentity.imap(validity, imap.getUID(it)) }
        if (identified.map { it.second }.distinct().size != identified.size) throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
        for ((message, id) in identified) {
            currentCoroutineContext().ensureActive()
            if (id in knownIds) continue
            val access = ImapRawFetch(imap)
            val uid = imap.getUID(message)
            val size = access.size(uid)
            val remote = if (size > ImapRawInputStream.MAX_RAW_BYTES) {
                RemoteMail(account.id, id, null, null, unsupported = true)
            } else {
                val context = currentCoroutineContext()
                ImapRawInputStream(uid, size, access::chunk) { context.ensureActive() }.use { raw ->
                    val parsed = try {
                        direct.kabu.alertkun.mail.common.StreamingMailMime.parse(raw)
                    } catch (_: ImapRawInputStream.RawLimitExceeded) {
                        null
                    } catch (violation: ImapRawInputStream.ProtocolViolation) {
                        throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR, violation)
                    }
                    // A MIME wrapper must never turn a transport/protocol failure into terminal mail.
                    raw.failure?.let { failure ->
                        if (failure is ImapRawInputStream.ProtocolViolation)
                            throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR, failure)
                        if (failure !is ImapRawInputStream.RawLimitExceeded) throw failure
                    }
                    if (parsed == null || parsed.unsupported || raw.failure is ImapRawInputStream.RawLimitExceeded)
                        RemoteMail(account.id, id, null, null, unsupported = true)
                    else RemoteMail(account.id, id, parsed.subject, parsed.from, parsed.headers, parsed.body, bodyFetched = true)
                }
            }
            consume(remote)
        }
    }

    internal suspend fun deleteCandidates(lookup: suspend (String, String) -> ReceivedMail?, now: Long,
        candidates: Set<String>): Pair<Set<String>, Set<String>> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptySet<String>() to emptySet()
        connectAndUse("SERVER_DELETE", Folder.READ_WRITE) { folder ->
            val imap = folder as IMAPFolder
            val validity = imap.uidValidity
            val deleted = mutableSetOf<String>()
            val absent = mutableSetOf<String>()
            // Validate the full local candidate set before changing any flags.
            val eligible = candidates.filter { id ->
                ServerDeleteEligibility.eligible(account.serverDeletePolicy, account.id, id, lookup(account.id, id), now)
            }
            for (id in eligible) {
                currentCoroutineContext().ensureActive()
                val fields = id.split(':')
                val storedValidity = fields.getOrNull(0)?.toLongOrNull()
                val uid = fields.getOrNull(1)?.toLongOrNull()
                if (fields.size != 2 || storedValidity == null || storedValidity <= 0 || uid == null || uid <= 0) {
                    throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
                }
                if (storedValidity != validity) { absent += id; continue }
                val message = imap.getMessageByUID(uid)
                if (message == null) absent += id
                else {
                    message.setFlag(Flags.Flag.DELETED, true)
                    deleted += id
                }
            }
            currentCoroutineContext().ensureActive()
            if (deleted.isNotEmpty()) imap.expunge() // Ordinary EXPUNGE is explicitly permitted.
            currentCoroutineContext().ensureActive()
            // No result escapes on flag/EXPUNGE/response/cancellation failures; Room stays PENDING.
            deleted to absent
        }
    }

    override fun remoteMessageId(folder: Folder, message: Message): String {
        val imapFolder = folder as? IMAPFolder ?: throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
        return try { RemoteIdentity.imap(imapFolder.uidValidity, imapFolder.getUID(message)) }
        catch (_: IllegalArgumentException) { throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR) }
    }
}
