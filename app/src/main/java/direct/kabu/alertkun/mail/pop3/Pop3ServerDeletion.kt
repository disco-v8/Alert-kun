package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.MailReceiverException
import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ReceivedMail
import direct.kabu.alertkun.model.ServerDeletePolicy
import direct.kabu.alertkun.monitoring.MonitoringFailureKind
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A separate session verifies every UIDL/Room lookup before DELE and checks every response.
 * QUIT is sent only on success, never from finally/close: an abort discards pending deletions.
 */
internal class Pop3ServerDeletion(private val account: MailAccount, private val password: String) {
    suspend fun deleteProcessed(lookup: suspend (String, String) -> ReceivedMail?, now: Long) = withContext(Dispatchers.IO) {
        if (!account.enabled || account.serverDeletePolicy == ServerDeletePolicy.NEVER ||
            account.protocol !in listOf(MailProtocol.POP3, MailProtocol.POP3S)) return@withContext
        try {
            require(listOf(account.username, password).none { '\r' in it || '\n' in it })
            Socket().use { transport ->
                transport.connect(InetSocketAddress(account.host, account.port), TIMEOUT)
                transport.soTimeout = TIMEOUT
                if (account.protocol == MailProtocol.POP3S) {
                    // Layer with the configured hostname to retain hostname verification and SNI.
                    val tls = SSLSocketFactory.getDefault().let { it as SSLSocketFactory }
                        .createSocket(transport, account.host, account.port, true) as SSLSocket
                    tls.use {
                        it.soTimeout = TIMEOUT
                        it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        it.startHandshake()
                        deleteInSession(it, lookup, now)
                    }
                } else {
                    deleteInSession(transport, lookup, now)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Existing monitoring failure model: local PROCESSED records remain safe for retry.
            throw MailReceiverException(MonitoringFailureKind.PROTOCOL_ERROR, failure)
        }
    }

    private suspend fun deleteInSession(socket: Socket, lookup: suspend (String, String) -> ReceivedMail?, now: Long) {
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        expectOk(reader)
        command(writer, "USER ${account.username}")
        expectOk(reader)
        command(writer, "PASS $password")
        expectOk(reader)
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
        val targets = mutableListOf<Int>()
        for ((number, uidl) in entries) {
            currentCoroutineContext().ensureActive()
            val local = lookup(account.id, uidl)
            if (ServerDeleteEligibility.eligible(account.serverDeletePolicy, account.id, uidl, local, now)) targets += number
        }
        // All lookups succeeded. Message numbers belong only to this session's verified listing.
        for (number in targets) {
            currentCoroutineContext().ensureActive()
            command(writer, "DELE $number")
            expectOk(reader)
        }
        currentCoroutineContext().ensureActive()
        command(writer, "QUIT")
        expectOk(reader)
    }

    private fun command(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    private fun expectOk(reader: BufferedReader) {
        val response = reader.readLine() ?: throw IOException("POP3 session interrupted")
        if (response != "+OK" && !response.startsWith("+OK ")) throw IOException("POP3 command rejected")
    }

    private companion object {
        const val TIMEOUT = 30_000
        val UIDL = Regex("[!-~]{1,70}")
    }
}
