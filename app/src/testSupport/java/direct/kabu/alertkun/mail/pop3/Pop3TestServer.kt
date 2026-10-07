package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.model.MailAccount
import direct.kabu.alertkun.model.MailProtocol
import direct.kabu.alertkun.model.ServerDeletePolicy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** Real TCP, disposable test mailbox. No production credentials or external server is used. */
class Pop3TestServer(uidls: List<String> = listOf("test-uid-1")) : AutoCloseable {
    private val listener = ServerSocket(0)
    private val connections = Collections.synchronizedList(mutableListOf<Socket>())
    private val messages = uidls.toMutableList()
    val commands = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var rejectDeleteNumber: Int? = null
    @Volatile var disconnectOnDeleteNumber: Int? = null
    @Volatile var duplicateUidl = false
    var subjects: Map<String, String> = emptyMap()
    @Volatile private var closed = false
    private val worker = thread(isDaemon = true, name = "pop3-test-server") {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            connections += socket
            thread(isDaemon = true) { try { socket.use { session(it) } } catch (_: Exception) { } finally { connections.remove(socket) } }
        }
    }

    fun account(policy: ServerDeletePolicy) = MailAccount("deletion-test", "Deletion test only", MailProtocol.POP3,
        "127.0.0.1", listener.localPort, "fixture-user", "fixture-key", false, null, true, policy)

    fun remaining(): List<String> = synchronized(messages) { messages.toList() }

    private fun session(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
        val snapshot = remaining()
        val deleted = mutableSetOf<Int>()
        fun reply(line: String) { writer.write(line + "\r\n"); writer.flush() }
        fun message(number: Int) = "From: fixture@example.invalid\r\nSubject: ${subjects[snapshot[number - 1]] ?: "ALERT test"}\r\nDate: Mon, 1 Jan 2001 00:00:00 +0000\r\nMessage-ID: <${snapshot[number - 1]}@example.invalid>\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nDisposable test message\r\n"
        reply("+OK dedicated test server")
        while (true) {
            val line = reader.readLine() ?: return // Pending DELE is discarded on abnormal close.
            val verb = line.substringBefore(' ').uppercase()
            val number = line.substringAfter(' ', "").substringBefore(' ').toIntOrNull()
            commands += if (verb in listOf("USER", "PASS")) verb else line
            when (verb) {
                "CAPA" -> { reply("+OK"); reply("UIDL"); reply("TOP"); reply(".") }
                "USER", "PASS", "NOOP" -> reply("+OK")
                "STAT" -> reply("+OK ${snapshot.size} ${snapshot.indices.sumOf { message(it + 1).length }}")
                "LIST" -> {
                    if (number != null) reply("+OK $number ${message(number).length}")
                    else { reply("+OK"); snapshot.indices.forEach { reply("${it + 1} ${message(it + 1).length}") }; reply(".") }
                }
                "UIDL" -> {
                    if (number != null) reply("+OK $number ${snapshot[number - 1]}")
                    else { reply("+OK"); snapshot.forEachIndexed { index, uidl -> reply("${index + 1} ${if (duplicateUidl) snapshot.first() else uidl}") }; reply(".") }
                }
                "TOP", "RETR" -> { reply("+OK"); writer.write(message(checkNotNull(number))); writer.write(".\r\n"); writer.flush() }
                "DELE" -> {
                    if (number == disconnectOnDeleteNumber) return
                    if (number == rejectDeleteNumber || number == null || number !in 1..snapshot.size) reply("-ERR deletion rejected")
                    else { deleted += number; reply("+OK marked") }
                }
                "RSET" -> { deleted.clear(); reply("+OK") }
                "QUIT" -> {
                    synchronized(messages) { deleted.forEach { messages.remove(snapshot[it - 1]) } }
                    reply("+OK committed")
                    return
                }
                else -> reply("-ERR unsupported")
            }
        }
    }

    override fun close() {
        closed = true
        listener.close()
        synchronized(connections) { connections.toList().forEach { it.close() } }
        worker.join(1_000)
    }
}
