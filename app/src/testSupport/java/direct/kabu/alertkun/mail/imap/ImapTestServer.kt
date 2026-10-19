package direct.kabu.alertkun.mail.imap

import direct.kabu.alertkun.model.*
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/** Disposable, loopback-only IMAP fixture. Never records LOGIN arguments. */
class ImapTestServer(val messages: MutableList<Mail> = mutableListOf(Mail(1))) : AutoCloseable {
    data class Mail(val uid: Long, val subject: String = "ALERT", val text: String = "ALERT body",
        val textSize: Int = text.toByteArray().size, val attachment: Boolean = false, var deleted: Boolean = false, val rawFile: java.io.File? = null, val rawText: String? = null, val reportedSize: Long? = null)
    private val listener = ServerSocket(0)
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    val commands = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var validity = 123L
    @Volatile var rejectStore = false
    @Volatile var rejectStoreNumber: Int? = null
    @Volatile var rejectExpunge = false
    @Volatile var loseExpungeResponse = false
    @Volatile var disconnectBody = false
    @Volatile var omitSize = false
    @Volatile var rejectSize = false
    @Volatile var responseUidDelta = 0L
    @Volatile var responseOffsetDelta = 0
    @Volatile var responseOverrun = 0
    @Volatile var omitOrigin = false
    @Volatile var faultAtOffset = 0
    @Volatile var disconnectAtOffset: Int? = null
    @Volatile var seen = false
    @Volatile var onRawChunk: ((Int) -> Unit)? = null
    val rawBytesSent = java.util.concurrent.atomic.AtomicLong()
    val closedSessions = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var closed = false
    private val worker = thread(isDaemon = true) {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            sockets += socket
            thread(isDaemon = true) { try { socket.use(::session) } catch (_: Exception) { } finally { sockets.remove(socket); closedSessions.incrementAndGet() } }
        }
    }
    fun account(policy: ServerDeletePolicy = ServerDeletePolicy.NEVER, id: String = "imap-fixture") = MailAccount(
        id,"IMAP fixture",MailProtocol.IMAP4,"127.0.0.1",listener.localPort,"fixture-user","fixture-key",false,null,true,policy)

    private fun session(socket: Socket) {
        socket.soTimeout = 10000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val output = socket.getOutputStream().buffered(8192)
        fun line(value: String) { output.write((value+"\r\n").toByteArray(Charsets.US_ASCII)); output.flush() }
        line("* OK disposable IMAP fixture")
        while (true) {
            val command = reader.readLine() ?: return
            val tag = command.substringBefore(' ')
            val request = command.substringAfter(' ')
            val verb = request.substringBefore(' ').uppercase()
            commands += if(verb=="LOGIN" || verb=="AUTHENTICATE") verb else request
            when(verb) {
                "CAPABILITY" -> { line("* CAPABILITY IMAP4rev1 UNSELECT");line("$tag OK capability") }
                "LOGIN","NOOP" -> line("$tag OK")
                "EXAMINE","SELECT" -> {
                    line("* FLAGS (\\Seen \\Deleted)")
                    line("* ${messages.size} EXISTS");line("* 0 RECENT")
                    line("* OK [UIDVALIDITY $validity]");line("* OK [UIDNEXT ${(messages.maxOfOrNull { it.uid } ?: 0)+1}]")
                    line("$tag OK [${if(verb=="EXAMINE") "READ-ONLY" else "READ-WRITE"}] opened")
                }
                "UNSELECT","CLOSE" -> line("$tag OK closed")
                "FETCH","UID" -> {
                    val byUid=verb=="UID"
                    val fetch=if(byUid) request.substringAfter("UID ") else request
                    if(!fetch.startsWith("FETCH ")) { line("$tag BAD unsupported");continue }
                    val range=fetch.substringAfter("FETCH ").substringBefore(' ')
                    val args=fetch.substringAfter(range).trim()
                    val selected=messages.toList().withIndex().filter { (index,mail) ->
                        val value=if(byUid) mail.uid else (index+1).toLong()
                        range.split(',').any { item ->
                            val bounds=item.split(':')
                            val start=bounds.first().toLongOrNull() ?: Long.MAX_VALUE
                            val end=if(bounds.size==1) start else if(bounds[1]=="*") Long.MAX_VALUE else bounds[1].toLong()
                            value in start..end
                        }
                    }
                    if (args.contains("BODYSTRUCTURE") || args.contains("ENVELOPE")) {
                        line("$tag BAD forbidden high-level fetch"); continue
                    }
                    if (args.contains("RFC822.SIZE") && rejectSize) { line("$tag NO scalar unavailable"); continue }
                    for((index,mail) in selected) {
                        val fields=mutableListOf("UID ${mail.uid}")
                        if(args.contains("RFC822.SIZE") && !omitSize) fields += "RFC822.SIZE ${mail.reportedSize ?: rawSize(mail)}"
                        if(args.contains("FLAGS")) fields += "FLAGS (${if(mail.deleted) "\\Deleted" else ""})"
                        val section=Regex("BODY(?:\\.PEEK)?\\[([^]]*)](?:<(\\d+)\\.(\\d+)>)?").find(args)
                        if(section!=null) {
                            val name=section.groupValues[1]
                            val offset=section.groupValues[2].toIntOrNull() ?: 0
                            val limit=section.groupValues[3].toIntOrNull() ?: error("Only partial fetch is supported")
                            check(name.isEmpty()) { "Only raw MIME fetch is supported" }
                            if(!args.contains("BODY.PEEK[")) seen=true
                            if(disconnectBody || disconnectAtOffset?.let { offset>=it } == true) return
                            onRawChunk?.invoke(offset)
                            val fault = offset >= faultAtOffset
                            val count=minOf(limit+(if(fault)responseOverrun else 0),(rawSize(mail)-offset).coerceAtLeast(0).toInt())
                            val returnedOffset=offset+(if(fault)responseOffsetDelta else 0)
                            val origin=if(omitOrigin) "" else "<$returnedOffset>"
                            fields[0]="UID ${mail.uid+(if(fault)responseUidDelta else 0)}"
                            line("* ${index+1} FETCH (${fields.joinToString(" ")} BODY[$name]$origin {$count}")
                            writeRaw(mail,output,offset,count)
                            rawBytesSent.addAndGet(count.toLong())
                            output.write(")\r\n".toByteArray());output.flush()
                        } else line("* ${index+1} FETCH (${fields.joinToString(" ")})")
                    }
                    line("$tag OK fetched")
                }
                "STORE" -> {
                    val number=request.substringAfter("STORE ").substringBefore(' ').toInt()
                    if(rejectStore || number==rejectStoreNumber) line("$tag NO flag rejected")
                    else {
                        messages[number-1].deleted=true
                        line("$tag OK flags stored")
                    }
                }
                "EXPUNGE" -> {
                    if(rejectExpunge) { line("$tag NO expunge rejected");continue }
                    var index=0
                    while(index<messages.size) {
                        if(messages[index].deleted) { messages.removeAt(index);if(!loseExpungeResponse)line("* ${index+1} EXPUNGE") }
                        else index++
                    }
                    if(loseExpungeResponse)return
                    line("$tag OK expunged")
                }
                "LOGOUT" -> { line("* BYE");line("$tag OK logout");return }
                else -> line("$tag BAD unsupported")
            }
        }
    }
    private fun prefix(mail: Mail): ByteArray = ("Subject: ${mail.subject}\r\nFrom: Fixture <fixture@example.invalid>\r\n" +
        "Message-ID: <fixture@example.invalid>\r\n" +
        if(mail.attachment) "Content-Type: multipart/mixed; boundary=camera\r\n\r\n--camera\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
        else "Content-Type: text/plain; charset=UTF-8\r\n\r\n").toByteArray(Charsets.UTF_8)
    private fun tail(mail: Mail) = if(mail.attachment)
        "\r\n--camera\r\nContent-Type: image/jpeg\r\nContent-Disposition: attachment; filename=camera.jpg\r\nContent-Transfer-Encoding: base64\r\n\r\n".toByteArray() else ByteArray(0)
    private fun ending(mail: Mail) = if(mail.attachment) "\r\n--camera--\r\n".toByteArray() else ByteArray(0)
    private fun rawSize(mail: Mail): Long = mail.rawFile?.length() ?: mail.rawText?.toByteArray(Charsets.UTF_8)?.size?.toLong()
        ?: (prefix(mail).size.toLong()+mail.textSize+tail(mail).size+(if(mail.attachment)10*1024*1024 else 0)+ending(mail).size)
    private fun writeRaw(mail: Mail, out: java.io.OutputStream, offset: Int, count: Int) {
        if(mail.rawFile!=null) {
            java.io.RandomAccessFile(mail.rawFile,"r").use { input ->
                input.seek(offset.toLong());val buffer=ByteArray(8192);var left=count
                while(left>0){val read=input.read(buffer,0,minOf(left,buffer.size));check(read>0);out.write(buffer,0,read);left-=read}
            };return
        }
        if(mail.rawText!=null){out.write(mail.rawText.toByteArray(Charsets.UTF_8),offset,count);return}
        val header=prefix(mail);val text=mail.text.toByteArray(Charsets.UTF_8);val tail=tail(mail);val end=ending(mail)
        val buffer=ByteArray(8192);var sent=0;val total=rawSize(mail)
        while(sent<count){
            val amount=minOf(buffer.size,count-sent)
            for(i in 0 until amount){
                val at=offset+sent+i
                buffer[i]=when {
                    at<header.size -> header[at]
                    at<header.size+mail.textSize -> text.getOrNull(at-header.size) ?: 'x'.code.toByte()
                    at<header.size+mail.textSize+tail.size -> tail[at-header.size-mail.textSize]
                    at<total-end.size -> 'A'.code.toByte()
                    else -> end[at-(total-end.size).toInt()]
                }
            }
            out.write(buffer,0,amount);sent+=amount
        }
    }
    override fun close() {
        closed=true;listener.close()
        synchronized(sockets){ sockets.toList().forEach { it.close() } }
        worker.join(1000)
    }
}
