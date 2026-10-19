package direct.kabu.alertkun.mail.imap

import direct.kabu.alertkun.mail.common.*
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

abstract class ImapReceiveContract {
    protected open val temporaryDirectory: java.io.File get() = java.io.File(checkNotNull(System.getProperty("java.io.tmpdir")))
    private suspend fun receive(server: ImapTestServer): List<RemoteMail> = buildList {
        ImapMailReceiver(server.account(),"fixture-secret").receive({emptySet()}) { add(it) }
        assertFalse("BODYSTRUCTURE forbidden",server.commands.any { it.contains("BODYSTRUCTURE") })
        assertFalse("Receive cannot mark seen",server.seen)
    }
    @Test fun oversizedScalarSkipsAllBodyAndUnknownSizeStopsAtRawLimit() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1,reportedSize=16777217))).use { server ->
            assertTrue(receive(server).single().unsupported)
            assertEquals(0L,server.rawBytesSent.get())
            assertFalse(server.commands.any { it.contains("BODY") })
        }
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1,textSize=17*1024*1024))).use { server ->
            server.omitSize=true
            assertTrue(receive(server).single().unsupported)
            assertEquals(16777216L,server.rawBytesSent.get())
            assertEquals(256,server.commands.count { it.contains("BODY.PEEK[]") })
        }
    }
    @Test fun scalarAbsentOrRejectedAllowsBoundedRawAndExactShortEof() = runBlocking {
        for(rejected in listOf(false,true)) ImapTestServer().use { server ->
            server.rejectSize=rejected;server.omitSize=!rejected
            assertEquals("ALERT body",receive(server).single().body)
            assertEquals(1,server.commands.count { it.contains("BODY.PEEK[]<0.65536>") })
        }
    }
    @Test fun sequentialOffsetsAndExactEofPreserveBodyPrefix() = runBlocking {
        for(size in listOf(65536,65537,131072,16777216)) {
            val header="Content-Type: text/plain; charset=UTF-8\r\n\r\n"
            val file=java.io.File.createTempFile("imap-boundary-",".eml",temporaryDirectory)
            try {
                file.outputStream().buffered().use { out ->
                    out.write(header.toByteArray());val bytes=ByteArray(8192){'x'.code.toByte()};var left=size-header.length
                    while(left>0){val count=minOf(left,bytes.size);out.write(bytes,0,count);left-=count}
                }
                ImapTestServer(mutableListOf(ImapTestServer.Mail(1,rawFile=file))).use { server ->
                    assertEquals("x".repeat(16384),receive(server).single().body)
                    assertEquals(size.toLong(),server.rawBytesSent.get())
                    val offsets=server.commands.mapNotNull { Regex("BODY.PEEK\\[\\]<(\\d+)\\.(\\d+)>").find(it)?.groupValues?.get(1)?.toInt() }
                    assertEquals(offsets.indices.map { it*65536 },offsets)
                }
            } finally { assertTrue(file.delete()) }
        }
    }
    @Test fun protocolViolationsCloseConnectionSaveNothingAndAllowNextCheck() = runBlocking {
        for(fault in listOf("uid","back","gap","duplicate","overrun","size-short","size-long","origin")) {
            ImapTestServer(mutableListOf(ImapTestServer.Mail(1,textSize=140000,
                reportedSize=when(fault){"size-short"->1000L;"size-long"->150000L;else->null}))).use { server ->
                when(fault){
                    "uid"->server.responseUidDelta=1
                    "back"->server.responseOffsetDelta=-1
                    "gap"->server.responseOffsetDelta=1
                    "duplicate"->{server.faultAtOffset=65536;server.responseOffsetDelta=-65536}
                    "overrun"->server.responseOverrun=1024
                    "origin"->{server.faultAtOffset=65536;server.omitOrigin=true}
                }
                var saves=0
                try { ImapMailReceiver(server.account(),"fixture-secret").receive({emptySet()}) { saves++ };fail("Expected protocol failure: $fault") }
                catch(failure:MailReceiverException){assertEquals(fault,direct.kabu.alertkun.monitoring.MonitoringFailureKind.PROTOCOL_ERROR,failure.failureKind)}
                assertEquals(0,saves)
                assertTrue(server.commands.any { it=="LOGOUT" })
                server.responseUidDelta=0;server.responseOffsetDelta=0;server.responseOverrun=0;server.omitOrigin=false
                server.messages[0]=ImapTestServer.Mail(1)
                assertEquals("ALERT body",receive(server).single().body)
            }
        }
    }
    @Test fun rawScanContinuesAfterBodyFullAndChoosesLaterPlainAlternative() = runBlocking {
        val raw="Content-Type: multipart/alternative; boundary=x\r\n\r\n--x\r\nContent-Type: text/html\r\n\r\n"+
            "a".repeat(80000)+"\r\n--x\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\npreferred plain\r\n--x--\r\n"
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1,rawText=raw))).use { server ->
            assertEquals("preferred plain",receive(server).single().body)
            assertEquals(raw.toByteArray().size.toLong(),server.rawBytesSent.get())
        }
    }
    @Test fun cameraAttachmentTransfersButNeverEntersBoundedBody() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1,attachment=true))).use { server ->
            assertEquals("ALERT body",receive(server).single().body)
            assertTrue(server.rawBytesSent.get()>10*1024*1024)
            assertEquals(1,server.commands.count { it=="LOGIN" })
        }
    }
    @Test fun mimeLimitsStopFetchEarlyAndContinueNextMessage() = runBlocking {
        for(case in listOf("parts","parts257","depth17","preamble","header")) {
            val file=java.io.File.createTempFile("imap-limits-",".eml",temporaryDirectory)
            try {
                direct.kabu.alertkun.mail.pop3.MimeFixtures.write(file,case)
                ImapTestServer(mutableListOf(ImapTestServer.Mail(1,rawFile=file),ImapTestServer.Mail(2))).use { server ->
                    val mails=receive(server)
                    assertTrue(case,mails[0].unsupported);assertEquals("ALERT body",mails[1].body)
                    if(case=="parts") assertTrue("Stop before full raw",server.rawBytesSent.get()<file.length()/2)
                }
            } finally { assertTrue(file.delete()) }
        }
    }
    @Test fun cancellationStopsNextChunkAndClosesSession() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1,textSize=200000))).use { server ->
            val parent=kotlinx.coroutines.Job()
            var saves=0
            server.onRawChunk={parent.cancel()}
            try { kotlinx.coroutines.withContext(parent) {
                ImapMailReceiver(server.account(),"fixture-secret").receive({emptySet()}) { saves++ }
            };fail("Expected cancellation") } catch (_:kotlinx.coroutines.CancellationException) {}
            assertEquals(0,saves)
            assertEquals(1,server.commands.count { it.contains("BODY.PEEK[]") })
            assertTrue(server.commands.any { it=="LOGOUT" })
            parent.cancel()
        }
    }
    @Test fun chunkBoundaryInsideTransferEncodingAndCharsetIsContinuous() = runBlocking {
        val before="Content-Type: multipart/alternative; boundary=x\r\n\r\n--x\r\nContent-Type: text/html\r\n\r\n<style>"
        val after="</style>\r\n--x\r\nContent-Type: text/plain\r\n\r\nboundary text\r\n--x--\r\n"
        for(split in listOf(after.indexOf("\r\n")+1,after.indexOf("--x")+2,after.indexOf("Content-Type")+5)) {
            val raw=before+"x".repeat(65536-before.length-split)+after
            ImapTestServer(mutableListOf(ImapTestServer.Mail(1,rawText=raw))).use { server ->
                assertEquals("boundary text",receive(server).single().body)
                assertEquals(2,server.commands.count { it.contains("BODY.PEEK[]") })
            }
        }
        for(encoding in listOf("8bit","base64","quoted-printable","iso-2022-jp")) {
            val text=if(encoding=="iso-2022-jp") "日本語の通知" else "日本語🙂 end"
            val charset=if(encoding=="iso-2022-jp") "ISO-2022-JP" else "UTF-8"
            val transfer=if(encoding=="iso-2022-jp") "8bit" else encoding
            val data=text.toByteArray(java.nio.charset.Charset.forName(charset))
            val encoded=java.io.ByteArrayOutputStream().apply {
                jakarta.mail.internet.MimeUtility.encode(this,transfer).use { it.write(data) }
            }.toByteArray()
            val before="Content-Type: multipart/alternative; boundary=x\r\n\r\n--x\r\nContent-Type: text/html\r\n\r\n<style>"
            val after="</style>\r\n--x\r\nContent-Type: text/plain; charset=$charset\r\nContent-Transfer-Encoding: $transfer\r\n\r\n"
            val raw=java.io.ByteArrayOutputStream().apply {
                write(before.toByteArray());write(ByteArray(65535-before.length-after.length){'x'.code.toByte()})
                write(after.toByteArray());write(encoded);write("\r\n--x--\r\n".toByteArray())
            }.toByteArray()
            val file=java.io.File.createTempFile("imap-encoding-",".eml",temporaryDirectory)
            try {
                file.writeBytes(raw)
                ImapTestServer(mutableListOf(ImapTestServer.Mail(1,rawFile=file))).use { server ->
                    assertEquals(encoding,text,receive(server).single().body)
                    assertEquals(2,server.commands.count { it.contains("BODY.PEEK[]") })
                }
            } finally { assertTrue(file.delete()) }
        }
    }
    @Test fun partialConnectionLossPreservesEarlierSaveAndRetrySkipsIt() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1),ImapTestServer.Mail(2,textSize=200000))).use { server ->
            server.disconnectAtOffset=65536
            val known=mutableSetOf<String>();val saved=mutableListOf<RemoteMail>()
            val receiver=ImapMailReceiver(server.account(),"fixture-secret")
            try {
                receiver.receive({known}) { saved+=it;known+=it.remoteMessageId }
                fail("Expected transport failure")
            } catch(failure:MailReceiverException) {
                assertTrue(direct.kabu.alertkun.monitoring.NetworkStabilization.isTransientNetworkRetryCandidate(failure))
            }
            assertEquals(listOf("123:1"),saved.map { it.remoteMessageId })
            server.disconnectAtOffset=null
            val commandsBefore=server.commands.size
            receiver.receive({known}) { saved+=it;known+=it.remoteMessageId }
            assertEquals(listOf("123:1","123:2"),saved.map { it.remoteMessageId })
            assertFalse(server.commands.drop(commandsBefore).any { it.startsWith("UID FETCH 1 ") })
        }
    }
    private val secrets=object:CredentialStore {
        override fun load(key:String)="fixture-secret"
        override fun save(key:String,password:String)=error("Read-only fixture")
        override fun delete(key:String)=error("Read-only fixture")
    }
    private fun saved(id:String="123:1",account:String="imap-fixture",state:MailProcessingState=MailProcessingState.PROCESSED) =
        ReceivedMail("saved-$id",account,id,1700000000000L,null,emptyMap(),null,null,false,state,false,null)

    @Test fun knownUidNeverFetchesHeadersOrBodyAndClosesOneSession() = runBlocking {
        ImapTestServer().use { server ->
            ImapMailReceiver(server.account(),"fixture-secret").receive({setOf("123:1")}) { fail("Known mail") }
            assertEquals(1,server.commands.count { it=="LOGIN" })
            assertEquals(1,server.commands.count { it.startsWith("EXAMINE ") })
            assertFalse(server.commands.any { it.contains("BODY") || it.contains("ENVELOPE") })
            assertTrue(server.commands.any { it.startsWith("UNSELECT") || it=="CLOSE" })
        }
    }

    @Test fun multipleUnknownMessagesShareOneReadOnlySession() = runBlocking {
        ImapTestServer((1..5).map { ImapTestServer.Mail(it.toLong()) }.toMutableList()).use { server ->
            val mails=mutableListOf<RemoteMail>()
            ImapMailReceiver(server.account(),"fixture-secret").receive({emptySet()},mails::add)
            assertEquals(5,mails.size)
            assertTrue(mails.all { it.bodyFetched && it.body=="ALERT body" })
            assertEquals(1,server.commands.count { it=="LOGIN" })
            assertEquals(1,server.commands.count { it.startsWith("EXAMINE ") })
            assertFalse(server.commands.any { it.startsWith("STORE ") })
            assertTrue(server.commands.filter { it.contains("BODY[") }.isEmpty())
        }
    }

    @Test fun receiveFailureClosesAndCanReconnect() = runBlocking {
        ImapTestServer().use { server ->
            val receiver=ImapMailReceiver(server.account(),"fixture-secret")
            server.disconnectBody=true
            try { receiver.receive({emptySet()}) {}; fail("Failure expected") } catch (_:MailReceiverException) {}
            server.disconnectBody=false
            val mails=mutableListOf<RemoteMail>()
            receiver.receive({emptySet()},mails::add)
            assertEquals(1,mails.size);assertEquals(2,server.commands.count { it=="LOGIN" })
        }
    }

    @Test fun immediateDeletionUsesSeparateReadWriteSessionAndExpungeOnce() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1),ImapTestServer.Mail(2,deleted=true))).use { server ->
            val account=server.account(ServerDeletePolicy.IMMEDIATELY)
            val result=ImapMailReceiver(account,"fixture-secret").deleteCandidates({_,id->saved(id)},1700000001000L,setOf("123:1"))
            assertEquals(setOf("123:1"),result.first);assertTrue(result.second.isEmpty())
            assertTrue(server.messages.isEmpty()) // Ordinary EXPUNGE includes pre-existing \Deleted.
            assertEquals(1,server.commands.count { it.startsWith("SELECT ") })
            assertEquals(1,server.commands.count { it=="EXPUNGE" })
            assertEquals(1,server.commands.count { it.startsWith("STORE ") })
        }
    }

    @Test fun absentAndUidValidityMismatchNeverFlagOtherMail() = runBlocking {
        for(id in listOf("123:99","122:1")) {
            ImapTestServer().use { server ->
                val result=ImapMailReceiver(server.account(ServerDeletePolicy.IMMEDIATELY),"fixture-secret")
                    .deleteCandidates({_,remote->saved(remote)},1700000001000L,setOf(id))
                assertEquals(setOf(id),result.second);assertTrue(result.first.isEmpty())
                assertFalse(server.commands.any { it.startsWith("STORE ") || it=="EXPUNGE" })
                assertEquals(1,server.messages.size)
            }
        }
    }

    @Test fun flagExpungeAndLostResponseNeverResolveRoomAndRemainRetryable() = runBlocking {
        for(failure in listOf("flag","expunge","response")) {
            ImapTestServer().use { server ->
                server.rejectStore=failure=="flag";server.rejectExpunge=failure=="expunge";server.loseExpungeResponse=failure=="response"
                var persistCalls=0
                val receiver=AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)),secrets,
                    localProcessedMails={listOf(saved())},resolveDeletes={_,_,_->persistCalls++;0 to 0})
                try { receiver.deleteProcessedMessages({_,id->saved(id)},1700000001000L);fail("Failure expected") }
                catch (_:MailReceiverException) {}
                assertEquals(0,persistCalls)
                server.rejectStore=false;server.rejectExpunge=false;server.loseExpungeResponse=false
                receiver.deleteProcessedMessages({_,id->saved(id)},1700000001000L)
                assertEquals(1,persistCalls)
            }
        }
    }

    @Test fun noCandidatesNeverPolicyConnectionTestAndReceivedStateNeverDelete() = runBlocking {
        ImapTestServer().use { server ->
            for(policy in listOf(ServerDeletePolicy.NEVER,ServerDeletePolicy.IMMEDIATELY)) {
                val receiver=AccountMailReceiver(listOf(server.account(policy)),secrets,localProcessedMails={emptyList()})
                receiver.deleteProcessedMessages({_,_->null},1700000001000L)
            }
            assertTrue(server.commands.isEmpty())
            AccountMailReceiver(listOf(server.account(ServerDeletePolicy.IMMEDIATELY)),secrets).testConnection(server.account())
            assertFalse(server.commands.any { it.startsWith("STORE ") || it=="EXPUNGE" })
            server.commands.clear()
            ImapMailReceiver(server.account(ServerDeletePolicy.IMMEDIATELY),"fixture-secret")
                .deleteCandidates({_,id->saved(id,state=MailProcessingState.RECEIVED)},1700000001000L,setOf("123:1"))
            assertFalse(server.commands.any { it.startsWith("STORE ") || it=="EXPUNGE" })
        }
    }

    @Test fun partialFlagFailureDoesNotExpungeOrResolveAndNextAttemptCanFinish() = runBlocking {
        ImapTestServer(mutableListOf(ImapTestServer.Mail(1),ImapTestServer.Mail(2))).use { server ->
            val receiver=ImapMailReceiver(server.account(ServerDeletePolicy.IMMEDIATELY),"fixture-secret")
            server.rejectStoreNumber=2
            try { receiver.deleteCandidates({_,id->saved(id)},1700000001000L,linkedSetOf("123:1","123:2"));fail("Failure expected") }
            catch (_:MailReceiverException) {}
            assertTrue(server.messages.first().deleted)
            assertFalse(server.messages.last().deleted)
            assertFalse(server.commands.any { it=="EXPUNGE" })
            server.rejectStoreNumber=null
            val result=receiver.deleteCandidates({_,id->saved(id)},1700000001000L,linkedSetOf("123:1","123:2"))
            assertEquals(setOf("123:1","123:2"),result.first)
            assertTrue(server.messages.isEmpty())
        }
    }

    @Test fun dayBoundariesAndUtcCalendarMonthGateDeletionSession() = runBlocking {
        val first=1700000000000L
        val cases=listOf(ServerDeletePolicy.AFTER_1_DAY to 1,ServerDeletePolicy.AFTER_1_WEEK to 7,
            ServerDeletePolicy.AFTER_10_DAYS to 10,ServerDeletePolicy.AFTER_15_DAYS to 15)
        for((policy,days) in cases) {
            ImapTestServer().use { server ->
                val receiver=AccountMailReceiver(listOf(server.account(policy)),secrets,localProcessedMails={listOf(saved())},
                    resolveDeletes={_,deleted,absent->deleted.size to absent.size})
                val due=first+days*86400000L
                receiver.deleteProcessedMessages({_,id->saved(id)},due-1)
                assertTrue(server.commands.isEmpty())
                receiver.deleteProcessedMessages({_,id->saved(id)},due)
                assertTrue(server.messages.isEmpty())
            }
        }
        val january=java.time.Instant.parse("2024-01-31T12:00:00Z").toEpochMilli()
        val february=java.time.Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()
        ImapTestServer().use { server ->
            val mail=saved().copy(receivedAtEpochMillis=january)
            val receiver=AccountMailReceiver(listOf(server.account(ServerDeletePolicy.AFTER_1_MONTH)),secrets,
                localProcessedMails={listOf(mail)},resolveDeletes={_,deleted,absent->deleted.size to absent.size})
            receiver.deleteProcessedMessages({_,_->mail},february-1);assertTrue(server.commands.isEmpty())
            receiver.deleteProcessedMessages({_,_->mail},february);assertTrue(server.messages.isEmpty())
        }
    }
}
