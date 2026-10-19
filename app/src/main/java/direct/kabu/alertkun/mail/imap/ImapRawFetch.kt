package direct.kabu.alertkun.mail.imap

import jakarta.mail.MessagingException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.Argument
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.RFC822SIZE
import org.eclipse.angus.mail.imap.protocol.UID
import java.io.IOException

/** Only numeric UID FETCH commands; no envelope, content API, or BODYSTRUCTURE. */
internal class ImapRawFetch(private val folder: IMAPFolder) {
    private fun responses(uid: Long, items: String): List<FetchResponse> {
        require(uid > 0)
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { protocol ->
            val args = Argument().apply { writeNumber(uid); writeArgument(Argument().apply { writeAtom(items) }) }
            val responses = protocol.command("UID FETCH", args)
            val last = responses.last()
            protocol.notifyResponseHandlers(responses)
            // Angus retains transport exceptions on synthetic BYE rather than as the cause of
            // the eventual ConnectionException. Preserve that type for the existing bounded retry.
            if (last.isSynthetic && last.exception is IOException)
                throw java.io.EOFException("IMAP response interrupted").apply { initCause(last.exception) }
            protocol.handleResult(last)
            responses.filterIsInstance<FetchResponse>()
        } as List<FetchResponse>
    }
    fun size(uid: Long): Long = try {
        val data = responses(uid, "UID RFC822.SIZE")
        if (data.size != 1 || data.single().getItem(UID::class.java)?.uid != uid)
            throw ImapRawInputStream.ProtocolViolation()
        data.single().getItem(RFC822SIZE::class.java)?.size?.takeIf { it >= 0 } ?: -1L
    } catch (failure: MessagingException) {
        if (generateSequence<Throwable>(failure) { it.cause }.take(16).any { it is CommandFailedException }) -1L
        else throw failure
    }
    fun chunk(uid: Long, offset: Int, count: Int): ImapRawInputStream.Chunk = try {
        val data = responses(uid, "UID BODY.PEEK[]<$offset.$count>")
        if (data.size != 1) throw ImapRawInputStream.ProtocolViolation()
        val response = data.single()
        val actualUid = response.getItem(UID::class.java)?.uid ?: throw ImapRawInputStream.ProtocolViolation()
        val bodies = (0 until response.itemCount).map { response.getItem(it) }.filterIsInstance<BODY>()
        val body = bodies.singleOrNull() ?: throw ImapRawInputStream.ProtocolViolation()
        if (body.section != "") throw ImapRawInputStream.ProtocolViolation()
        val bytes = body.byteArray ?: throw ImapRawInputStream.ProtocolViolation()
        ImapRawInputStream.Chunk(actualUid, body.origin, bytes.bytes, bytes.start, bytes.count)
    } catch (failure: MessagingException) {
        throw ImapRawInputStream.ProtocolViolation().apply { initCause(failure) }
    }
}
