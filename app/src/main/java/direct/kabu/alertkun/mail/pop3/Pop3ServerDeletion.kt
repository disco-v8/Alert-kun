package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.mail.common.MailConnectionSettings
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import direct.kabu.alertkun.monitoring.ObservationSink
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A separate session verifies every UIDL/Room lookup before DELE and checks every response.
 * QUIT is sent only on success, never from finally/close: an abort discards pending deletions.
 */
internal class Pop3ServerDeletion(private val account: MailAccount, private val password: String,
    private val trace: (String) -> Unit = {},
    private val socketProvider: (MailAccount) -> Socket = { MailConnectionSettings.openSocket(it) },
    private val observation: ObservationSink = { _, _ -> }) {
    suspend fun deleteProcessed(lookup: suspend (String, String) -> ReceivedMail?, now: Long, candidates: Set<String> = emptySet()): Pair<Set<String>, Set<String>> = withContext(Dispatchers.IO) {
        if (!account.enabled || account.serverDeletePolicy == ServerDeletePolicy.NEVER ||
            account.protocol !in listOf(MailProtocol.POP3, MailProtocol.POP3S)) return@withContext emptySet<String>() to emptySet<String>()
        var phase = "connect_tls"
        var connected = false
        var connectStarted: Long? = null
        fun observe(event: String, category: String? = null, exceptionClass: String? = null) {
            runCatching { observation(event, mapOf("accountId" to account.id, "protocol" to account.protocol.name,
                "operation" to "SERVER_DELETE", "elapsedMs" to connectStarted?.let { System.nanoTime() / 1_000_000L - it },
                "category" to category, "exceptionClass" to exceptionClass)) }
        }
        val setPhase: (String) -> Unit = { phase = it; trace("POP3 deletion stage=$it") }
        try {
            require(listOf(account.username, password).none { '\r' in it || '\n' in it })
            setPhase("connect_tls")
            connectStarted = System.nanoTime() / 1_000_000L
            observe("MAIL_CONNECT_START")
            val resolved = socketProvider(account).use { transport ->
                connected = true
                observe("MAIL_CONNECT_SUCCESS")
                setPhase("greeting")
                deleteInSession(transport, lookup, now, candidates, setPhase)
            }
            trace("POP3 deletion outcome=success")
            resolved
        } catch (cancelled: CancellationException) {
            observe("MAIL_CONNECT_CANCELLED")
            throw cancelled
        } catch (failure: Exception) {
            // Existing monitoring failure model: local PROCESSED records remain safe for retry.
            val kind = MailConnectionSettings.classify(failure).let {
                if (it == MonitoringFailureKind.SERVER_UNAVAILABLE) MonitoringFailureKind.PROTOCOL_ERROR else it
            }
            trace("POP3 deletion outcome=failure stage=$phase kind=$kind type=${failure.javaClass.simpleName}")
            observe(if (connected) "MAIL_SESSION_FAILURE" else "MAIL_CONNECT_FAILURE", kind.name, failure.javaClass.simpleName)
            throw MailReceiverException(kind, failure)
        }
    }

    private suspend fun deleteInSession(socket: Socket, lookup: suspend (String, String) -> ReceivedMail?, now: Long,
        candidates: Set<String>, setPhase: (String) -> Unit): Pair<Set<String>, Set<String>> {
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        expectOk(reader)
        setPhase("auth")
        command(writer, "USER ${account.username}")
        expectOk(reader, MonitoringFailureKind.AUTHENTICATION_FAILURE)
        command(writer, "PASS $password")
        expectOk(reader, MonitoringFailureKind.AUTHENTICATION_FAILURE)
        setPhase("uidl")
        command(writer, "UIDL")
        expectOk(reader)
        val entries = linkedMapOf<Int, String>()
        val uidls = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: throw IOException("Incomplete POP3 UIDL response")
            if (line == ".") break
            val parts = line.split(' ')
            val number = parts.firstOrNull()?.toIntOrNull()
            val uidl = parts.getOrNull(1)
            if (parts.size != 2 || number == null || number <= 0 || uidl == null ||
                !UIDL.matches(uidl) || entries.put(number, uidl) != null || !uidls.add(uidl)) {
                throw IOException("Invalid or ambiguous POP3 UIDL listing")
            }
        }
        val missing = candidates - uidls
        runCatching { observation("SERVER_UIDL_MATCH", mapOf("accountId" to account.id,
            "protocol" to account.protocol.name, "count" to uidls.size,
            "matched" to (candidates.size - missing.size), "missing" to missing.size)) }
        val targets = mutableListOf<Int>()
        setPhase("lookup")
        for ((number, uidl) in entries) {
            currentCoroutineContext().ensureActive()
            val local = lookup(account.id, uidl)
            if (ServerDeleteEligibility.eligible(account.serverDeletePolicy, account.id, uidl, local, now)) targets += number
        }
        // All lookups succeeded. Message numbers belong only to this session's verified listing.
        trace("POP3 deletion eligibleCount=${targets.size}")
        setPhase("dele")
        for (number in targets) {
            currentCoroutineContext().ensureActive()
            command(writer, "DELE $number")
            expectOk(reader)
        }
        currentCoroutineContext().ensureActive()
        setPhase("quit")
        command(writer, "QUIT")
        expectOk(reader)
        return targets.map { entries.getValue(it) }.toSet() to missing
    }

    private fun command(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    private fun expectOk(reader: BufferedReader, rejectedKind: MonitoringFailureKind = MonitoringFailureKind.PROTOCOL_ERROR) {
        val response = reader.readLine() ?: throw IOException("POP3 session interrupted")
        if (response != "+OK" && !response.startsWith("+OK ")) throw MailReceiverException(rejectedKind)
    }

    private companion object {
        val UIDL = Regex("[!-~]{1,70}")
    }
}
