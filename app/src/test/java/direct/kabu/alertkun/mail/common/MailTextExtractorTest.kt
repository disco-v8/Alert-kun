package direct.kabu.alertkun.mail.common

import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailTextExtractorTest {
    @Test fun extractsPlainText() {
        val message = MimeMessage(Session.getInstance(Properties())).apply { setText("plain body", "UTF-8") }
        assertEquals("plain body", MailTextExtractor.extract(message))
    }

    @Test fun prefersPlainTextFromAlternative() {
        val multipart = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText("plain", "UTF-8") })
            addBodyPart(MimeBodyPart().apply { setContent("<b>html</b>", "text/html; charset=UTF-8") })
        }
        val message = MimeMessage(Session.getInstance(Properties())).apply { setContent(multipart); saveChanges() }
        assertEquals("plain", MailTextExtractor.extract(message))
    }

    @Test fun ignoresAttachment() {
        val multipart = MimeMultipart().apply {
            addBodyPart(MimeBodyPart().apply { disposition = jakarta.mail.Part.ATTACHMENT; fileName = "secret.txt"; setText("attachment", "UTF-8") })
        }
        val message = MimeMessage(Session.getInstance(Properties())).apply { setContent(multipart); saveChanges() }
        assertNull(MailTextExtractor.extract(message))
    }
}
