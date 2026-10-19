package direct.kabu.alertkun.mail.pop3

import direct.kabu.alertkun.mail.common.StreamingMailMime

import direct.kabu.alertkun.mail.common.BoundedMailText
import direct.kabu.alertkun.model.*
import direct.kabu.alertkun.rules.RuleEngine
import direct.kabu.alertkun.rules.RuleEvaluation
import jakarta.mail.internet.MimeUtility
import java.io.*
import org.junit.Assert.*
import org.junit.Test

/** The same real production parser and generated fixtures run on JVM and Android. */
abstract class MailBodyBoundsContract {
    protected open fun temporaryDirectory(): File = File(checkNotNull(System.getProperty("java.io.tmpdir")))

    private fun parse(raw: String) = StreamingMailMime.parse(raw.byteInputStream())
    private fun text(value: String, encoding: String = "7bit", charset: String = "UTF-8"): StreamingMailMime.Result {
        val output = ByteArrayOutputStream()
        output.write("Content-Type: text/plain; charset=$charset\r\nContent-Transfer-Encoding: $encoding\r\n\r\n".toByteArray())
        MimeUtility.encode(output, encoding).use { it.write(value.toByteArray(java.nio.charset.Charset.forName(charset))) }
        return StreamingMailMime.parse(output.toByteArray().inputStream())
    }

    @Test fun exactUtf8BodyPrefixAndRuleSearchRange() {
        for (size in listOf(100,16384,20000)) {
            val result = text("a".repeat(size))
            assertFalse(result.unsupported)
            assertEquals(minOf(size,16384),result.body.toByteArray().size)
            assertEquals("a".repeat(minOf(size,16384)),result.body)
        }
        val body = text("MATCH"+"a".repeat(16384)+"AFTER").body
        val rule = AlertRule("r","Fixture","MATCH",RuleTarget.BODY,1,null,VibrationPattern.NONE,AlarmMode.ONCE,true)
        assertTrue(RuleEngine().evaluate(null,body,true,listOf(rule)) is RuleEvaluation.Match)
        assertEquals(RuleEvaluation.NoMatch,RuleEngine().evaluate(null,body,true,listOf(rule.copy(searchText="AFTER"))))
    }

    @Test fun decodedEncodingsAndUnicodeBoundaries() {
        for (encoding in listOf("7bit","base64","quoted-printable")) {
            assertEquals("a".repeat(16384), text("a".repeat(20000),encoding).body)
        }
        for ((value, count) in listOf("あ" to 5461,"😀" to 4096)) {
            val result = text(value.repeat(20000),"base64")
            assertFalse(result.unsupported)
            assertEquals(value.repeat(count),result.body)
            assertTrue(result.body.toByteArray().size<=16384)
            assertEquals(result.body,String(result.body.toByteArray(),Charsets.UTF_8))
        }
        assertEquals("日本語の監視通知",text("日本語の監視通知","quoted-printable","ISO-2022-JP").body)
        assertEquals("あ".repeat(5461),text("あ".repeat(6000),"base64","ISO-2022-JP").body)
    }

    @Test fun alternativeSelectsPlainOnceRegardlessOfOrder() {
        for (plainFirst in listOf(true,false)) {
            val plain="Content-Type: text/plain\r\n\r\nplain\r\n"
            val html="Content-Type: text/html\r\n\r\n<b>html</b>\r\n"
            val children=if(plainFirst) listOf(plain,html) else listOf(html,plain)
            val result=parse("Content-Type: multipart/alternative; boundary=b\r\n\r\n"+children.joinToString("") { "--b\r\n$it" }+"--b--\r\n")
            assertFalse(result.unsupported);assertEquals("plain",result.body)
        }
        assertEquals("visible",parse("Content-Type: multipart/alternative; boundary=b\r\n\r\n--b\r\nContent-Type: text/html\r\n\r\n<script>hidden</script><b>visible</b>\r\n--b--\r\n").body)
        assertEquals("A&amp;B",parse("Content-Type: text/html\r\n\r\n<p>A&amp;B</p>").body)
    }

    @Test fun mixedTextOrderAndTotalBoundExcludeAttachmentsAndForwardedMessages() {
        val result=parse("Content-Type: multipart/mixed; boundary=b\r\n\r\n"+
            "--b\r\nContent-Type: text/plain\r\n\r\n"+"a".repeat(10240)+"\r\n"+
            "--b\r\nContent-Type: text/plain\r\n\r\n"+"b".repeat(10240)+"\r\n"+
            "--b\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=log.txt\r\n\r\nATTACHMENT\r\n"+
            "--b\r\nContent-Type: text/plain; name=log.txt\r\n\r\nNAMED\r\n"+
            "--b\r\nContent-Type: message/rfc822\r\n\r\nSubject: Forwarded\r\n\r\nFORWARDED\r\n--b--\r\n")
        assertFalse(result.unsupported)
        assertEquals("a".repeat(10240)+"\n"+"b".repeat(6143),result.body)
    }

    @Test fun giantHtmlIsReadWithConstantScratchSpaceAndStopsAtTextLimit() {
        var read=0
        val reader=object:Reader(){
            override fun close() {}
            override fun read(buffer:CharArray,offset:Int,length:Int):Int {
                val count=minOf(length,100*1024*1024-read)
                if(count==0)return -1
                java.util.Arrays.fill(buffer,offset,offset+count,'x'); read+=count;return count
            }
        }
        val result=BoundedMailText().apply { read(reader,true) }.value()
        assertEquals(16384,result.toByteArray().size)
        assertTrue(read<=16385)
    }

    @Test fun namedTextAttachmentsAndAttachedMessagesNeverEnterShortBody() {
        val result=parse("Content-Type: multipart/mixed; boundary=b\r\n\r\n"+
            "--b\r\nContent-Type: text/plain\r\n\r\nnormal\r\n"+
            "--b\r\nContent-Type: text/plain\r\nContent-Disposition: inline; filename = log.txt\r\n\r\nFILENAME\r\n"+
            "--b\r\nContent-Type: text/plain; name = log.txt\r\n\r\nNAMED\r\n"+
            "--b\r\nContent-Type: text/plain\r\nContent-Disposition: inline; filename*=UTF-8''log.txt\r\n\r\nEXTENDED\r\n"+
            "--b\r\nContent-Type: message/rfc822\r\n\r\nSubject: Forwarded\r\n\r\nFORWARDED\r\n--b--\r\n")
        assertFalse(result.unsupported);assertEquals("normal",result.body)
    }

    @Test fun malformedAndHugeStructureStopsBeforeObjectTreeAndAttachmentIsDiscarded() {
        for (mode in listOf("preamble","header","nested","depth17","parts257","parts","epilogue","headers","folded-header","line","attachment","plain-large","malformed")) {
            val fixture=File.createTempFile("mail-bounds-",".eml",temporaryDirectory())
            try {
                MimeFixtures.write(fixture,mode)
                assertTrue(fixture.length()<=16777216)
                var bytesRead=0L
                val result=fixture.inputStream().use { original ->
                    val counted=object:FilterInputStream(original){
                        override fun read():Int = `in`.read().also { if(it>=0) bytesRead++ }
                        override fun read(buffer:ByteArray,offset:Int,length:Int):Int = `in`.read(buffer,offset,length).also { if(it>0)bytesRead+=it }
                    }
                    StreamingMailMime.parse(counted)
                }
                assertEquals(mode,mode !in listOf("attachment","plain-large"),result.unsupported)
                assertTrue(result.body.toByteArray().size<=16384)
                if(mode=="parts") assertTrue(bytesRead<65536)
                if(mode=="nested") assertTrue(bytesRead<fixture.length())
                if(mode=="attachment") assertEquals("x",result.body)
                if(mode=="plain-large") assertEquals("x".repeat(16384),result.body)
            } finally { assertTrue(fixture.delete()) }
        }
    }
}
