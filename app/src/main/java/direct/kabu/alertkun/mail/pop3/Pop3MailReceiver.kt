package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.StreamingMailMime

import direct.kabu.alertkun.mail.common.JakartaMailReceiver
import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.RemoteIdentity
import direct.kabu.alertkun.mail.common.RemoteMail
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.FetchProfile
import jakarta.mail.UIDFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.angus.mail.pop3.POP3Folder
import org.eclipse.angus.mail.pop3.POP3Message

class Pop3MailReceiver(account: MailAccount, password: String, trace: (String) -> Unit = {},
    observation: ObservationSink = { _, _ -> },
    private val temporaryDirectory: File = File(System.getProperty("java.io.tmpdir"), "alertkun-pop3"),
) : JakartaMailReceiver(account, password, observation) {
    private val deletion = Pop3ServerDeletion(account, password, trace, observation = observation)

    override suspend fun receiveMessages(folder: Folder, knownIds: Set<String>, consume: suspend (RemoteMail) -> Unit) {
        val pop3 = folder as? POP3Folder ?: protocolFailure()
        val messages = pop3.messages // References only; no header access, ENVELOPE, or getSize().
        pop3.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        val identified = messages.map { it to remoteMessageId(pop3, it) }
        if (identified.map { it.second }.distinct().size != identified.size) protocolFailure()
        val sizes = readSizes(pop3, messages.size)
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
        // Only our private scratch files, before any new one is opened. The Worker serializes checks.
        val leftovers = temporaryDirectory.listFiles() ?: throw java.io.IOException("Cannot inspect private mail scratch directory")
        leftovers.filter { it.name.startsWith("raw-") && it.name.endsWith(".eml") }.forEach { check(it.delete()) }
        for ((message, uidl) in identified) {
            currentCoroutineContext().ensureActive()
            if (uidl in knownIds) continue
            if (sizes.getValue(message.messageNumber) > StreamingMailMime.MAX_RAW_BYTES) {
                consume(RemoteMail(account.id, uidl, null, null, unsupported = true))
                continue
            }
            val scratch = File.createTempFile("raw-", ".eml", temporaryDirectory)
            var failure: Throwable? = null
            try {
                scratch.outputStream().buffered(8192).use { output ->
                    // With cachewriteto=false (provider default), this public API streams RETR without raw heap caching.
                    (message as POP3Message).writeTo(RawLimitOutput(output), null)
                }
                currentCoroutineContext().ensureActive()
                val parsed = scratch.inputStream().buffered(8192).use { StreamingMailMime.parse(it) }
                consume(RemoteMail(account.id, uidl, parsed.subject, parsed.from, parsed.headers, parsed.body,
                    bodyFetched = !parsed.unsupported, unsupported = parsed.unsupported))
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!scratch.delete() && scratch.exists()) {
                    val cleanup = java.io.IOException("Private mail scratch cleanup failed")
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun readSizes(folder: POP3Folder, count: Int): Map<Int, Long> {
        val sizes = mutableMapOf<Int, Long>()
        val input = folder.listCommand() ?: protocolFailure()
        input.use {
            val line = StringBuilder(80)
            fun acceptLine() {
                val fields = line.toString().trim().split(Regex("\\s+"))
                val number = fields.getOrNull(0)?.toIntOrNull() ?: protocolFailure()
                val size = fields.getOrNull(1)?.toLongOrNull() ?: protocolFailure()
                if (fields.size != 2 || number !in 1..count || size < 0 || sizes.put(number, size) != null) protocolFailure()
                line.setLength(0)
            }
            while (true) {
                val byte = it.read()
                if (byte < 0) break
                if (byte == 10) acceptLine()
                else if (byte != 13) {
                    if (byte !in 32..126 || line.length >= 80) protocolFailure()
                    line.append(byte.toChar())
                }
            }
            if (line.isNotEmpty()) protocolFailure()
        }
        if (sizes.size != count) protocolFailure()
        return sizes
    }

    private class RawLimitOutput(output: OutputStream) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            if (++count > StreamingMailMime.MAX_RAW_BYTES) protocolFailure()
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length.toLong() > StreamingMailMime.MAX_RAW_BYTES - count) protocolFailure()
            count += length
            out.write(bytes, offset, length)
        }
    }

    override suspend fun deleteProcessedMessages(lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        deletion.deleteProcessed(lookup, now)
    }
    internal suspend fun deleteCandidates(lookup: suspend (String, String) -> ReceivedMail?, now: Long,
        candidates: Set<String>) = deletion.deleteProcessed(lookup, now, candidates)

    override fun remoteMessageId(folder: Folder, message: Message): String = try {
        RemoteIdentity.pop3((folder as? POP3Folder)?.getUID(message))
    } catch (_: IllegalArgumentException) { protocolFailure() }

    private companion object {
        fun protocolFailure(): Nothing = throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR)
    }
}
